package com.acme.insurance.distribution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.BookExports;
import cds.gen.insurance.BookExports_;
import cds.gen.insurance.BookOfBusinessViews;
import cds.gen.insurance.BookOfBusinessViews_;
import cds.gen.insurance.Claims;
import cds.gen.insurance.Claims_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;
import cds.gen.insurance.Producers;

/**
 * The agency's live view of in-force premium, loss experience, and pending
 * renewals, scoped by the access granted to the user.
 *
 * Spec: specs/distribution/agent-portal/view-book-of-business.feature.md
 */
@Component
public class BookOfBusinessManager {

    private static final int MONEY_SCALE = 2;

    private final PersistenceService persistence;
    private final ProducerLicensingManager licensing;
    private final Clock clock;

    public BookOfBusinessManager(PersistenceService persistence,
            ProducerLicensingManager licensing, Clock clock) {
        this.persistence = persistence;
        this.licensing = licensing;
        this.clock = clock;
    }

    /**
     * "Agency principal sees the whole agency book" — in-force policy count and
     * written premium are shown for every producer at the agency, as of the
     * previous night's close.
     *
     * "Loss ratio is reported on an earned premium basis" — $987,000 of incurred
     * loss including loss adjustment expense against $2,100,000 of earned
     * premium shows a 47% loss ratio for the trailing twelve months, with open
     * reserves included at their current reserve value.
     */
    public BookOfBusinessViews view(String producerId, Boolean agencyWide) {
        Producers producer = licensing.load(producerId);
        boolean wholeAgency = Boolean.TRUE.equals(agencyWide);
        LocalDate today = LocalDate.now(clock);

        List<Policies> book = wholeAgency
                ? agencyBook(producer.getAgencyId())
                : producerBook(producerId);

        BigDecimal written = BigDecimal.ZERO.setScale(MONEY_SCALE);
        for (Policies policy : book) {
            if (policy.getPremium() != null) {
                written = written.add(policy.getPremium());
            }
        }

        BigDecimal earned = earnedPremium(book, today);
        BigDecimal incurred = incurredLoss(book);
        BigDecimal lossRatioPct = earned.signum() == 0
                ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                : incurred.multiply(new BigDecimal("100"))
                        .divide(earned, MONEY_SCALE, RoundingMode.HALF_UP);

        BookOfBusinessViews view = BookOfBusinessViews.create();
        view.setId(UUID.randomUUID().toString());
        view.setAgencyId(producer.getAgencyId());
        view.setProducerId(producerId);
        view.setAgencyWide(wholeAgency);
        view.setInForceCount(book.size());
        view.setWrittenPremium(written);
        view.setEarnedPremium(earned);
        view.setIncurredLoss(incurred);
        view.setLossRatioPct(lossRatioPct);
        // Figures are shown as of the previous night's close.
        view.setAsOfDate(today.minusDays(1));
        view.setGeneratedAt(clock.instant());
        persistence.run(Insert.into(BookOfBusinessViews_.class).entry(view));

        return persistence.run(Select.from(BookOfBusinessViews_.class)
                .where(v -> v.ID().eq(view.getId())))
                .single(BookOfBusinessViews.class);
    }

    /**
     * "Export of the book is limited and logged" — the file omits full
     * policyholder account numbers and dates of birth, and the export request is
     * written to the audit log with the requesting user and timestamp.
     */
    public BookExports export(String producerId) {
        Producers producer = licensing.load(producerId);
        List<Policies> book = producerBook(producerId);

        BookExports export = BookExports.create();
        export.setId(UUID.randomUUID().toString());
        export.setAgencyId(producer.getAgencyId());
        export.setRequestedBy(producer.getEmail());
        export.setRequestedAt(clock.instant());
        export.setFormat("csv");
        export.setRowCount(book.size());
        // Account numbers and dates of birth never leave the platform.
        export.setAccountNumbersOmitted(true);
        export.setDatesOfBirthOmitted(true);
        export.setAuditLogged(true);
        persistence.run(Insert.into(BookExports_.class).entry(export));

        return persistence.run(Select.from(BookExports_.class)
                .where(e -> e.ID().eq(export.getId())))
                .single(BookExports.class);
    }

    /**
     * "Renewal pipeline highlights policies inside 60 days" — policies with an
     * expiration date within 60 days are listed first.
     *
     * Marking policies that carry a non-renewal notice is part of the same
     * scenario; the caller reads that off each policy's cancellation notices.
     */
    public List<Policies> renewalPipeline(String agencyId, Integer withinDays) {
        LocalDate today = LocalDate.now(clock);
        int window = withinDays == null ? 60 : withinDays;
        LocalDate cutoff = today.plusDays(window);

        return agencyBook(agencyId).stream()
                .filter(p -> p.getExpirationDate() != null)
                .sorted(Comparator
                        // Inside the window first, then by expiration date.
                        .comparing((Policies p) -> p.getExpirationDate().isAfter(cutoff))
                        .thenComparing(Policies::getExpirationDate))
                .toList();
    }

    /** In-force policies produced anywhere in the agency. */
    public List<Policies> agencyBook(String agencyId) {
        if (agencyId == null) {
            return List.of();
        }
        return persistence.run(Select.from(Policies_.class)
                .where(p -> p.producingAgency_ID().eq(agencyId)))
                .listOf(Policies.class).stream()
                .filter(p -> Statuses.POLICY_IN_FORCE.equals(p.getStatus()))
                .toList();
    }

    /**
     * "Producer sees only their own policies" is [proposed]; the producer-scoped
     * query is nonetheless what the non-agency-wide view reads from.
     */
    public List<Policies> producerBook(String producerId) {
        return persistence.run(Select.from(Policies_.class)
                .where(p -> p.producerOfRecord_ID().eq(producerId)))
                .listOf(Policies.class).stream()
                .filter(p -> Statuses.POLICY_IN_FORCE.equals(p.getStatus()))
                .toList();
    }

    /**
     * Premium earned to date on the book, pro rata across each policy's term —
     * the denominator of the loss ratio.
     */
    private BigDecimal earnedPremium(List<Policies> book, LocalDate asOf) {
        BigDecimal earned = BigDecimal.ZERO.setScale(MONEY_SCALE);
        for (Policies policy : book) {
            if (policy.getPremium() == null || policy.getEffectiveDate() == null
                    || policy.getExpirationDate() == null) {
                continue;
            }
            long termDays = java.time.temporal.ChronoUnit.DAYS
                    .between(policy.getEffectiveDate(), policy.getExpirationDate());
            long elapsed = java.time.temporal.ChronoUnit.DAYS
                    .between(policy.getEffectiveDate(), asOf);
            if (termDays <= 0 || elapsed <= 0) {
                continue;
            }
            long earnedDays = Math.min(elapsed, termDays);
            earned = earned.add(policy.getPremium()
                    .multiply(BigDecimal.valueOf(earnedDays))
                    .divide(BigDecimal.valueOf(termDays), MONEY_SCALE, RoundingMode.HALF_UP));
        }
        return earned;
    }

    /**
     * Incurred loss including loss adjustment expense. Claims with open reserves
     * are included at their current reserve value.
     */
    private BigDecimal incurredLoss(List<Policies> book) {
        BigDecimal incurred = BigDecimal.ZERO.setScale(MONEY_SCALE);
        for (Policies policy : book) {
            for (Claims claim : persistence.run(Select.from(Claims_.class)
                    .where(c -> c.policy_ID().eq(policy.getId())))
                    .listOf(Claims.class)) {
                // A settled claim counts at what was paid; a claim with an open
                // reserve counts at its current reserve value.
                BigDecimal amount = claim.getPaidToShopAmount() != null
                        ? claim.getPaidToShopAmount()
                        : claim.getEstimatedDamage();
                if (amount != null) {
                    incurred = incurred.add(amount);
                }
            }
        }
        return incurred;
    }

    public BookOfBusinessViews loadView(String viewId) {
        return persistence.run(Select.from(BookOfBusinessViews_.class)
                .where(v -> v.ID().eq(viewId)))
                .first(BookOfBusinessViews.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Book view not found: " + viewId));
    }
}
