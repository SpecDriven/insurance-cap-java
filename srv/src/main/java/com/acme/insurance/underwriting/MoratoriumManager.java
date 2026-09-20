package com.acme.insurance.underwriting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.ports.EmailMessage;
import com.acme.insurance.ports.EmailPort;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.BlockedRequests;
import cds.gen.insurance.BlockedRequests_;
import cds.gen.insurance.Moratoria;
import cds.gen.insurance.MoratoriumAreas;
import cds.gen.insurance.Moratoria_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;

/**
 * A temporary suspension of new business and coverage increases in an area
 * under imminent threat.
 *
 * Spec: specs/underwriting/appetite/apply-moratorium.feature.md
 */
@Component
public class MoratoriumManager {

    private final PersistenceService persistence;
    private final EmailPort email;
    private final Clock clock;

    public MoratoriumManager(PersistenceService persistence, EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.email = email;
        this.clock = clock;
    }

    /**
     * "Named storm moratorium opens on a watch" — new business binding is
     * suspended in the named counties immediately, and the moratorium
     * identifier, counties, and open time are broadcast to agents.
     */
    public Moratoria open(String moratoriumId, String peril, String areaType,
            java.util.Collection<String> areaCodes) {
        LocalDateTime openedAt = LocalDateTime.now(clock);

        List<MoratoriumAreas> areas = new ArrayList<>();
        for (String code : areaCodes) {
            MoratoriumAreas area = MoratoriumAreas.create();
            area.setId(UUID.randomUUID().toString());
            area.setAreaType(areaType);
            area.setAreaCode(code);
            areas.add(area);
        }

        Moratoria moratorium = Moratoria.create();
        moratorium.setId(UUID.randomUUID().toString());
        moratorium.setMoratoriumId(moratoriumId);
        moratorium.setPeril(peril);
        moratorium.setStatus(Statuses.MORATORIUM_OPEN);
        moratorium.setOpenedAt(openedAt.atZone(clock.getZone()).toInstant());
        moratorium.setManualHold(false);
        moratorium.setAreas(areas);
        persistence.run(Insert.into(Moratoria_.class).entry(moratorium));

        broadcastToAgents("Binding moratorium opened",
                "Moratorium " + moratoriumId + " (" + peril + ") is in effect for "
                        + String.join(", ", areaCodes) + " as of " + openedAt + ".");

        return load(moratorium.getId());
    }

    /**
     * "Moratorium lifts automatically after the event" — cancelling the watch
     * schedules the lift 72 hours later.
     */
    public Moratoria cancelStormWatch(String moratoriumId) {
        Moratoria moratorium = load(moratoriumId);
        LocalDateTime cancelledAt = LocalDateTime.now(clock);
        moratorium.setWatchCancelledAt(cancelledAt.atZone(clock.getZone()).toInstant());
        moratorium.setScheduledLiftAt(cancelledAt.plusHours(Statuses.MORATORIUM_LIFT_HOURS)
                .atZone(clock.getZone()).toInstant());
        persistence.run(Update.entity(Moratoria_.class).entry(moratorium));
        return load(moratoriumId);
    }

    /**
     * Lifts every moratorium whose scheduled lift has arrived, unless a manual
     * hold is placed. Agents are notified when binding reopens, and requests
     * blocked during the moratorium are queued for reprocessing.
     */
    public List<Moratoria> liftDue() {
        Instant now = Instant.now(clock);
        List<Moratoria> open = persistence.run(Select.from(Moratoria_.class)
                .where(m -> m.status().eq(Statuses.MORATORIUM_OPEN)))
                .listOf(Moratoria.class);

        List<Moratoria> lifted = new ArrayList<>();
        for (Moratoria moratorium : open) {
            if (Boolean.TRUE.equals(moratorium.getManualHold())) {
                continue; // "unless a manual hold is placed"
            }
            if (moratorium.getScheduledLiftAt() == null
                    || moratorium.getScheduledLiftAt().isAfter(now)) {
                continue;
            }
            moratorium.setStatus(Statuses.MORATORIUM_LIFTED);
            moratorium.setLiftedAt(now);
            persistence.run(Update.entity(Moratoria_.class).entry(moratorium));

            reprocessBlockedRequests(moratorium.getId());

            broadcastToAgents("Binding reopened",
                    "Moratorium " + moratorium.getMoratoriumId() + " lifted; binding is reopen.");
            lifted.add(load(moratorium.getId()));
        }
        return lifted;
    }

    /**
     * "Coverage increases blocked during a moratorium" — a dwelling limit
     * increase inside an active moratorium area is blocked until the moratorium
     * lifts, and the request is queued for automatic reprocessing after the lift.
     *
     * "Existing policies continue in force" — nothing here touches the in-force
     * policy: only the coverage-increase request is blocked.
     */
    public BlockedRequests requestCoverageIncrease(String policyId, BigDecimal requestedLimit,
            String zipCode) {
        Policies policy = persistence.run(Select.from(Policies_.class)
                .where(p -> p.ID().eq(policyId)))
                .first(Policies.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Policy not found: " + policyId));

        Moratoria active = activeFor(zipCode);
        if (active == null) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "No active moratorium for " + zipCode + "; process the endorsement normally");
        }

        BlockedRequests blocked = BlockedRequests.create();
        blocked.setId(UUID.randomUUID().toString());
        blocked.setMoratoriumId(active.getId());
        blocked.setPolicyId(policy.getId());
        blocked.setRequestType(Statuses.REQUEST_COVERAGE_INCREASE);
        blocked.setRequestedLimit(requestedLimit);
        blocked.setStatus(Statuses.REQUEST_QUEUED);
        blocked.setQueuedOn(LocalDate.now(clock));
        persistence.run(Insert.into(BlockedRequests_.class).entry(blocked));

        return loadBlocked(blocked.getId());
    }

    private void reprocessBlockedRequests(String moratoriumKey) {
        List<BlockedRequests> queued = persistence.run(Select.from(BlockedRequests_.class)
                .where(b -> b.moratorium_ID().eq(moratoriumKey)
                        .and(b.status().eq(Statuses.REQUEST_QUEUED))))
                .listOf(BlockedRequests.class);
        for (BlockedRequests request : queued) {
            request.setStatus(Statuses.REQUEST_REPROCESSED);
            request.setReprocessedOn(LocalDate.now(clock));
            persistence.run(Update.entity(BlockedRequests_.class).entry(request));
        }
    }

    /** The open moratorium covering an area code, if any. */
    public Moratoria activeFor(String areaCode) {
        List<Moratoria> open = persistence.run(Select.from(Moratoria_.class)
                .where(m -> m.status().eq(Statuses.MORATORIUM_OPEN))
                .columns(m -> m._all(), m -> m.areas().expand()))
                .listOf(Moratoria.class);
        return open.stream()
                .filter(m -> m.getAreas().stream()
                        .anyMatch(a -> a.getAreaCode().equalsIgnoreCase(areaCode)))
                .findFirst()
                .orElse(null);
    }

    private void broadcastToAgents(String subject, String body) {
        email.send(new EmailMessage("agents@acme-insurance.example", subject, body));
    }

    public Moratoria load(String moratoriumKey) {
        return persistence.run(Select.from(Moratoria_.class)
                .where(m -> m.ID().eq(moratoriumKey))
                .columns(m -> m._all(), m -> m.areas().expand()))
                .first(Moratoria.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Moratorium not found: " + moratoriumKey));
    }

    public BlockedRequests loadBlocked(String blockedId) {
        return persistence.run(Select.from(BlockedRequests_.class)
                .where(b -> b.ID().eq(blockedId)))
                .first(BlockedRequests.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Blocked request not found: " + blockedId));
    }
}
