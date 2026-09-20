package com.acme.insurance.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.DistributionTestBase;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.BookExports;
import cds.gen.insurance.BookOfBusinessViews;
import cds.gen.insurance.Claims;
import cds.gen.insurance.Claims_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Producers;

/**
 * Spec: specs/distribution/agent-portal/view-book-of-business.feature.md
 * "Producer sees only their own policies" and "Terminated agency retains
 * read-only visibility" are [proposed] and intentionally absent.
 */
@DisplayName("View Book of Business — specs/distribution/agent-portal/view-book-of-business.feature.md")
class ViewBookOfBusinessTest extends DistributionTestBase {

    @Test
    @DisplayName("Agency principal sees the whole agency book @v1")
    void agencyPrincipalSeesTheWholeAgencyBook() {
        // An agency principal signed in to the portal, with policies written by
        // two different producers
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers principal = newLicensedProducer(agency.getId(), "8800101", "OH");
        Producers other = newLicensedProducer(agency.getId(), "8800102", "OH");

        Policies first = insertAgencyPolicy(agency.getId(), principal.getId(),
                new BigDecimal("1200.00"), BASE_DATE.minusMonths(2), null);
        Policies second = insertAgencyPolicy(agency.getId(), other.getId(),
                new BigDecimal("800.00"), BASE_DATE.minusMonths(1), null);

        try {
            // The principal opens the book of business view
            BookOfBusinessViews view = db(() -> book.view(principal.getId(), true));

            // In-force policy count and written premium are shown for every
            // producer at the agency
            assertThat(view.getAgencyWide()).isTrue();
            assertThat(view.getInForceCount()).isEqualTo(2);
            assertThat(view.getWrittenPremium()).isEqualByComparingTo("2000.00");

            // Figures are shown as of the previous night's close
            assertThat(view.getAsOfDate()).isEqualTo(BASE_DATE.minusDays(1));
        } finally {
            deletePolicies(first.getId(), second.getId());
        }
    }

    @Test
    @DisplayName("Loss ratio is reported on an earned premium basis @v1")
    void lossRatioIsReportedOnAnEarnedPremiumBasis() {
        // An agency with $2,100,000 earned premium and $987,000 incurred loss
        // including loss adjustment expense. A policy fully earned over its term
        // carries the whole premium into earned premium.
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = newLicensedProducer(agency.getId(), "8800103", "OH");

        LocalDate effective = BASE_DATE.minusMonths(7);
        Policies policy = insertAgencyPolicy(agency.getId(), producer.getId(),
                new BigDecimal("2100000.00"), effective, null);

        // A claim with an open reserve, included at its current reserve value
        Claims claim = Claims.create();
        claim.setId(java.util.UUID.randomUUID().toString());
        claim.setClaimNumber("CLM-BOB-1");
        claim.setPolicyId(policy.getId());
        claim.setClaimType(Statuses.CLAIM_TYPE_AUTO_COLLISION);
        claim.setStatus(Statuses.CLAIM_OPEN_ASSIGNED);
        claim.setEstimatedDamage(new BigDecimal("987000.00"));
        dbRun(() -> persistence.run(com.sap.cds.ql.Insert.into(Claims_.class).entry(claim)));

        try {
            // The loss ratio panel is displayed
            BookOfBusinessViews view = db(() -> book.view(producer.getId(), true));

            // The six-month term is fully elapsed, so the whole premium is earned
            assertThat(view.getEarnedPremium()).isEqualByComparingTo("2100000.00");

            // Claims with open reserves are included at their current reserve value
            assertThat(view.getIncurredLoss()).isEqualByComparingTo("987000.00");

            // A 47% loss ratio is shown for the trailing twelve months
            assertThat(view.getLossRatioPct()).isEqualByComparingTo("47.00");
        } finally {
            dbRun(() -> persistence.run(com.sap.cds.ql.Delete.from(Claims_.class)
                    .where(c -> c.ID().eq(claim.getId()))));
            deletePolicies(policy.getId());
        }
    }

    @Test
    @DisplayName("Export of the book is limited and logged @v1")
    void exportOfTheBookIsLimitedAndLogged() {
        // A producer requesting a comma-separated export of the book
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = newLicensedProducer(agency.getId(), "8800104", "OH");
        Policies policy = insertAgencyPolicy(agency.getId(), producer.getId(),
                new BigDecimal("1200.00"), BASE_DATE.minusMonths(1), null);

        try {
            // The export is generated
            BookExports export = db(() -> book.export(producer.getId()));

            assertThat(export.getFormat()).isEqualTo("csv");
            assertThat(export.getRowCount()).isEqualTo(1);

            // The file omits full policyholder account numbers and dates of birth
            assertThat(export.getAccountNumbersOmitted()).isTrue();
            assertThat(export.getDatesOfBirthOmitted()).isTrue();

            // The export request is written to the audit log with the requesting
            // user and timestamp
            assertThat(export.getAuditLogged()).isTrue();
            assertThat(export.getRequestedBy()).isEqualTo(producer.getEmail());
            assertThat(export.getRequestedAt()).isNotNull();
        } finally {
            deletePolicies(policy.getId());
        }
    }

    @Test
    @DisplayName("Renewal pipeline highlights policies inside 60 days @v1")
    void renewalPipelineHighlightsPoliciesInside60Days() {
        // An agency with policies renewing across the next quarter
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = newLicensedProducer(agency.getId(), "8800105", "OH");

        // Expiring in 30 days, 45 days, and 85 days respectively
        Policies soonest = insertAgencyPolicy(agency.getId(), producer.getId(),
                new BigDecimal("500.00"), BASE_DATE.plusDays(30).minusMonths(6), null);
        Policies middle = insertAgencyPolicy(agency.getId(), producer.getId(),
                new BigDecimal("600.00"), BASE_DATE.plusDays(45).minusMonths(6), null);
        Policies later = insertAgencyPolicy(agency.getId(), producer.getId(),
                new BigDecimal("700.00"), BASE_DATE.plusDays(85).minusMonths(6), null);

        try {
            // The producer filters to the renewal pipeline
            List<Policies> pipeline = db(() -> book.renewalPipeline(agency.getId(), 60));

            // Policies with an expiration date within 60 days are listed first
            assertThat(pipeline).extracting(Policies::getId)
                    .containsExactly(soonest.getId(), middle.getId(), later.getId());
            assertThat(pipeline.get(0).getExpirationDate())
                    .isBeforeOrEqualTo(BASE_DATE.plusDays(60));
            assertThat(pipeline.get(1).getExpirationDate())
                    .isBeforeOrEqualTo(BASE_DATE.plusDays(60));
            assertThat(pipeline.get(2).getExpirationDate())
                    .isAfter(BASE_DATE.plusDays(60));
        } finally {
            deletePolicies(soonest.getId(), middle.getId(), later.getId());
        }
    }
}
