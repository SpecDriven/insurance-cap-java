package com.acme.insurance.distribution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.CommissionEntries;
import cds.gen.insurance.CommissionEntries_;
import cds.gen.insurance.CommissionRates;
import cds.gen.insurance.CommissionRates_;
import cds.gen.insurance.CommissionSchedules;
import cds.gen.insurance.CommissionSchedules_;
import cds.gen.insurance.CommissionStatements;
import cds.gen.insurance.CommissionStatements_;
import cds.gen.insurance.ContingentCommissions;
import cds.gen.insurance.ContingentCommissions_;

/**
 * Commission schedules by line and transaction type, the entries they produce,
 * chargebacks, sub-producer splits, and contingent commission.
 *
 * Spec: specs/distribution/agency-management/set-commission-schedule.feature.md
 */
@Component
public class CommissionManager {

    private static final int MONEY_SCALE = 2;

    private final PersistenceService persistence;
    private final AgencyOnboardingManager agencies;
    private final Clock clock;

    public CommissionManager(PersistenceService persistence, AgencyOnboardingManager agencies,
            Clock clock) {
        this.persistence = persistence;
        this.agencies = agencies;
        this.clock = clock;
    }

    /**
     * "New business and renewal rates differ by line" — publishing the standard
     * schedule gives each line its own new business, renewal, and endorsement
     * rates from the rate table, effective on the published date. The schedule
     * applies to policies with an effective date on or after that date, which the
     * rate lookup below enforces.
     */
    public List<CommissionSchedules> publishSchedule(String agencyId, String publishedBy,
            LocalDate effectiveDate) {
        agencies.load(agencyId);
        List<CommissionSchedules> published = new ArrayList<>();

        for (CommissionRates rate : persistence.run(Select.from(CommissionRates_.class))
                .listOf(CommissionRates.class)) {
            CommissionSchedules schedule = CommissionSchedules.create();
            schedule.setId(UUID.randomUUID().toString());
            schedule.setAgencyId(agencyId);
            schedule.setLineOfBusiness(rate.getLineOfBusiness());
            schedule.setNewBusinessPct(rate.getNewBusinessPct());
            schedule.setRenewalPct(rate.getRenewalPct());
            schedule.setEndorsementPct(rate.getEndorsementPct());
            schedule.setEffectiveDate(effectiveDate);
            schedule.setPublishedBy(publishedBy);
            persistence.run(Insert.into(CommissionSchedules_.class).entry(schedule));
            published.add(schedule);
        }
        return published;
    }

    /**
     * "Sub-producer split is honoured on shared business" — a placement shared at
     * a 70/30 split pays each agency code its share on its own statement, and the
     * split is recorded on each entry for audit.
     *
     * With no sharing agency the writing agency earns the whole commission.
     */
    public List<CommissionEntries> earnNewBusiness(String agencyId, String policyId,
            String lineOfBusiness, BigDecimal basisPremium, String sharedWithAgencyId,
            BigDecimal writingSharePct) {

        LocalDate today = LocalDate.now(clock);
        BigDecimal ratePct = rateFor(agencyId, lineOfBusiness, Statuses.TXN_NEW_BUSINESS, today);
        List<CommissionEntries> entries = new ArrayList<>();

        if (sharedWithAgencyId == null) {
            entries.add(insertEntry(agencyId, policyId, Statuses.TXN_NEW_BUSINESS, basisPremium,
                    ratePct, pct(basisPremium, ratePct), null, today));
            return entries;
        }

        BigDecimal writingShare = writingSharePct == null
                ? new BigDecimal("100.00") : writingSharePct;
        BigDecimal referringShare = new BigDecimal("100.00").subtract(writingShare);
        BigDecimal fullCommission = pct(basisPremium, ratePct);

        BigDecimal writingAmount = pct(fullCommission, writingShare);
        // The referring agency takes the remainder, so the two shares always sum
        // to the full commission even when the split does not divide evenly.
        BigDecimal referringAmount = fullCommission.subtract(writingAmount);

        entries.add(insertEntry(agencyId, policyId, Statuses.TXN_NEW_BUSINESS, basisPremium,
                ratePct, writingAmount, writingShare, today));
        entries.add(insertEntry(sharedWithAgencyId, policyId, Statuses.TXN_NEW_BUSINESS,
                basisPremium, ratePct, referringAmount, referringShare, today));
        return entries;
    }

    /**
     * "Chargeback follows a flat-cancelled policy" — a flat cancellation inside
     * the 10-day free-look charges back the full commission on the next
     * statement; a short-rate cancellation charges back only the unearned
     * portion.
     */
    public List<CommissionEntries> chargeback(String policyId, String cancellationType,
            BigDecimal unearnedPct) {
        LocalDate today = LocalDate.now(clock);
        List<CommissionEntries> earned = earnedEntriesFor(policyId);
        if (earned.isEmpty()) {
            throw new ServiceException(ErrorStatuses.NOT_FOUND,
                    "No commission earned on policy " + policyId);
        }

        List<CommissionEntries> chargebacks = new ArrayList<>();
        for (CommissionEntries entry : earned) {
            BigDecimal amount = Statuses.CANCELLATION_FLAT.equalsIgnoreCase(cancellationType)
                    ? entry.getAmount()
                    : pct(entry.getAmount(), unearnedPct == null ? BigDecimal.ZERO : unearnedPct);

            chargebacks.add(insertEntry(entry.getAgencyId(), policyId, Statuses.TXN_CHARGEBACK,
                    entry.getBasisPremium(), entry.getRatePct(), amount.negate(),
                    entry.getSharePct(), today));
        }
        return chargebacks;
    }

    /**
     * "Contingent commission requires a loss ratio threshold" — a 47% book loss
     * ratio including loss adjustment expense accrues 3% of earned premium after
     * the 90-day development window; no contingent is paid at 60% or higher.
     *
     * Bronze-tier agencies are not contingent eligible (onboard-agency.feature.md).
     */
    public ContingentCommissions calculateContingent(String agencyId, Integer contingentYear,
            BigDecimal earnedPremium, BigDecimal incurredLoss) {

        Agencies agency = agencies.load(agencyId);
        BigDecimal lossRatioPct = earnedPremium.signum() == 0
                ? BigDecimal.ZERO
                : incurredLoss.multiply(new BigDecimal("100"))
                        .divide(earnedPremium, MONEY_SCALE, RoundingMode.HALF_UP);

        boolean eligible = lossRatioPct.compareTo(Statuses.CONTINGENT_LOSS_RATIO_CEILING) < 0
                && !Statuses.TIER_BRONZE.equals(agency.getTier());
        BigDecimal ratePct = eligible ? Statuses.CONTINGENT_RATE_PCT : BigDecimal.ZERO;

        ContingentCommissions contingent = ContingentCommissions.create();
        contingent.setId(UUID.randomUUID().toString());
        contingent.setAgencyId(agencyId);
        contingent.setContingentYear(contingentYear);
        contingent.setEarnedPremium(earnedPremium);
        contingent.setIncurredLoss(incurredLoss);
        contingent.setLossRatioPct(lossRatioPct);
        contingent.setRatePct(ratePct);
        contingent.setAccruedAmount(pct(earnedPremium, ratePct));
        // The calculation runs after the 90-day development window closes.
        contingent.setCalculatedOn(LocalDate.now(clock));
        persistence.run(Insert.into(ContingentCommissions_.class).entry(contingent));

        if (eligible) {
            insertEntry(agencyId, null, Statuses.TXN_CONTINGENT, earnedPremium, ratePct,
                    contingent.getAccruedAmount(), null, contingent.getCalculatedOn());
        }
        return persistence.run(Select.from(ContingentCommissions_.class)
                .where(c -> c.ID().eq(contingent.getId())))
                .single(ContingentCommissions.class);
    }

    /**
     * "Unpaid premium owed by the agency is recovered" — the final statement
     * offsets collected but unremitted premium against earned commission and
     * refers any remaining shortfall to collections.
     *
     * Withheld entries (block-unlicensed-production.feature.md, "Commission is
     * withheld on business written without authority") are excluded from the
     * earned total until the compliance review clears them.
     */
    public CommissionStatements produceStatement(String agencyId, LocalDate periodStart,
            LocalDate periodEnd) {
        Agencies agency = agencies.load(agencyId);

        List<CommissionEntries> entries = persistence.run(Select.from(CommissionEntries_.class)
                .where(e -> e.agency_ID().eq(agencyId)))
                .listOf(CommissionEntries.class).stream()
                .filter(e -> e.getStatementId() == null)
                .filter(e -> !Boolean.TRUE.equals(e.getWithheld()))
                .filter(e -> inPeriod(e.getEarnedOn(), periodStart, periodEnd))
                .toList();

        BigDecimal earned = BigDecimal.ZERO.setScale(MONEY_SCALE);
        BigDecimal chargebacks = BigDecimal.ZERO.setScale(MONEY_SCALE);
        for (CommissionEntries entry : entries) {
            if (entry.getAmount().signum() < 0) {
                chargebacks = chargebacks.add(entry.getAmount().abs());
            } else {
                earned = earned.add(entry.getAmount());
            }
        }

        BigDecimal unremitted = agency.getUnremittedPremium() == null
                ? BigDecimal.ZERO.setScale(MONEY_SCALE) : agency.getUnremittedPremium();
        BigDecimal net = earned.subtract(chargebacks).subtract(unremitted);

        CommissionStatements statement = CommissionStatements.create();
        statement.setId(UUID.randomUUID().toString());
        statement.setAgencyId(agencyId);
        statement.setPeriodStart(periodStart);
        statement.setPeriodEnd(periodEnd);
        statement.setEarnedAmount(earned);
        statement.setChargebackAmount(chargebacks);
        statement.setPremiumOffset(unremitted);
        statement.setNetAmount(net);

        if (net.signum() < 0) {
            // No payment is issued; the negative balance carries forward.
            statement.setPaymentIssued(false);
            statement.setCarriedForward(net);
        } else {
            statement.setPaymentIssued(true);
            statement.setCarriedForward(BigDecimal.ZERO.setScale(MONEY_SCALE));
        }
        persistence.run(Insert.into(CommissionStatements_.class).entry(statement));

        for (CommissionEntries entry : entries) {
            entry.setStatementId(statement.getId());
            persistence.run(Update.entity(CommissionEntries_.class).entry(entry));
        }

        if (net.signum() < 0 && unremitted.signum() > 0) {
            // Any shortfall remaining after the offset is referred to collections.
            agency.setCollectionsReferral(net.abs());
            persistence.run(Update.entity(cds.gen.insurance.Agencies_.class).entry(agency));
        }
        return load(statement.getId());
    }

    /** Commission earned on the policy, excluding chargebacks already taken. */
    public List<CommissionEntries> earnedEntriesFor(String policyId) {
        return persistence.run(Select.from(CommissionEntries_.class)
                .where(e -> e.policy_ID().eq(policyId)))
                .listOf(CommissionEntries.class).stream()
                .filter(e -> !Statuses.TXN_CHARGEBACK.equals(e.getTransactionType()))
                .filter(e -> e.getAmount() != null && e.getAmount().signum() > 0)
                .toList();
    }

    /**
     * The rate for the line and transaction type from the schedule in force on
     * the transaction date. "Mid-term rate change does not disturb prior
     * transactions" is [proposed], but selecting the latest schedule effective on
     * or before the date is the same lookup, so it falls out of this query.
     */
    public BigDecimal rateFor(String agencyId, String lineOfBusiness, String transactionType,
            LocalDate on) {
        CommissionSchedules schedule = persistence.run(Select.from(CommissionSchedules_.class)
                .where(s -> s.agency_ID().eq(agencyId)
                        .and(s.lineOfBusiness().eq(lineOfBusiness))))
                .listOf(CommissionSchedules.class).stream()
                .filter(s -> s.getEffectiveDate() == null || !on.isBefore(s.getEffectiveDate()))
                .max(java.util.Comparator.comparing(CommissionSchedules::getEffectiveDate))
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "No commission schedule for " + lineOfBusiness + " on " + on));

        return switch (transactionType) {
            case Statuses.TXN_RENEWAL -> schedule.getRenewalPct();
            case Statuses.TXN_ENDORSEMENT -> schedule.getEndorsementPct();
            default -> schedule.getNewBusinessPct();
        };
    }

    /** Marks the policy's commission withheld pending compliance review. */
    public List<CommissionEntries> withhold(String policyId, String reason) {
        List<CommissionEntries> withheld = new ArrayList<>();
        for (CommissionEntries entry : earnedEntriesFor(policyId)) {
            entry.setWithheld(true);
            entry.setWithheldReason(reason);
            persistence.run(Update.entity(CommissionEntries_.class).entry(entry));
            withheld.add(entry);
        }
        return withheld;
    }

    public CommissionStatements load(String statementId) {
        return persistence.run(Select.from(CommissionStatements_.class)
                .where(s -> s.ID().eq(statementId)))
                .first(CommissionStatements.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Statement not found: " + statementId));
    }

    private CommissionEntries insertEntry(String agencyId, String policyId, String transactionType,
            BigDecimal basisPremium, BigDecimal ratePct, BigDecimal amount, BigDecimal sharePct,
            LocalDate earnedOn) {
        CommissionEntries entry = CommissionEntries.create();
        entry.setId(UUID.randomUUID().toString());
        entry.setAgencyId(agencyId);
        entry.setPolicyId(policyId);
        entry.setTransactionType(transactionType);
        entry.setBasisPremium(basisPremium);
        entry.setRatePct(ratePct);
        entry.setAmount(amount);
        entry.setSharePct(sharePct);
        entry.setEarnedOn(earnedOn);
        entry.setWithheld(false);
        persistence.run(Insert.into(CommissionEntries_.class).entry(entry));
        return entry;
    }

    private static boolean inPeriod(LocalDate date, LocalDate start, LocalDate end) {
        return date != null && !date.isBefore(start) && !date.isAfter(end);
    }

    private static BigDecimal pct(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent)
                .divide(new BigDecimal("100"), MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
