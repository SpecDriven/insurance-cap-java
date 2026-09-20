package com.acme.insurance.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.DistributionTestBase;

import cds.gen.insurance.Agencies;

/**
 * Spec: specs/distribution/agency-management/onboard-agency.feature.md
 * "Insufficient errors and omissions limits hold the file", "Ownership change
 * during onboarding restarts the review", and "Welcome kit is issued on
 * approval" are [proposed] and intentionally absent.
 */
@DisplayName("Onboard an Agency — specs/distribution/agency-management/onboard-agency.feature.md")
class OnboardAgencyTest extends DistributionTestBase {

    @Test
    @DisplayName("Agency submits a complete onboarding packet @v1")
    void agencySubmitsACompleteOnboardingPacket() {
        // A prospective Ohio agency with an active resident agency license,
        // $1,000,000 / $3,000,000 E&O, and a signed W-9
        Agencies agency = db(() -> onboarding.submitPacket(
                "Buckeye Insurance Partners LLC", "34-8871200", "OH", true,
                new BigDecimal("1000000.00"), new BigDecimal("3000000.00"), true,
                "Dana Whitfield", "dana.whitfield@buckeye.example"));

        // The agency record is created in pending-appointment status
        assertThat(agency.getStatus()).isEqualTo(Statuses.AGENCY_PENDING_APPOINTMENT);
        assertThat(agency.getResidentLicenseActive()).isTrue();
        assertThat(agency.getW9Signed()).isTrue();

        // A distribution manager is assigned to review within 5 business days
        assertThat(agency.getReviewerName()).isNotBlank();
        // 15 January 2026 is a Thursday; five business days on, skipping the
        // weekend and MLK Day on the 19th, is Friday 23 January
        assertThat(agency.getReviewDueOn()).isEqualTo(java.time.LocalDate.of(2026, 1, 23));
    }

    @Test
    @DisplayName("Duplicate tax identification number is rejected @v1")
    void duplicateTaxIdentificationNumberIsRejected() {
        // An existing appointed agency using federal tax ID 34-8871209
        db(() -> onboarding.submitPacket("Existing Agency LLC", "34-8871209", "OH", true,
                new BigDecimal("1000000.00"), new BigDecimal("3000000.00"), true,
                "Pat Mercer", "pat.mercer@existing.example"));

        // A second onboarding packet arrives with the same tax ID
        assertThatThrownBy(() -> db(() -> onboarding.submitPacket(
                "Second Agency LLC", "34-8871209", "OH", true,
                new BigDecimal("1000000.00"), new BigDecimal("3000000.00"), true,
                "Jamie Cole", "jamie.cole@second.example")))
                // The submission is rejected as a duplicate, and the submitter is
                // directed to add a branch location to the existing agency instead
                .hasMessageContaining("Duplicate tax identification number")
                .hasMessageContaining("branch location");
    }

    @Test
    @DisplayName("Principal with a prior license revocation is declined @v1")
    void principalWithAPriorLicenseRevocationIsDeclined() {
        // An agency principal whose Michigan producer license was revoked in 2021
        Agencies agency = newAgency();

        // The background and regulatory review completes
        Agencies reviewed = db(() -> onboarding.completeRegulatoryReview(agency.getId(), true));

        // Onboarding is declined
        assertThat(reviewed.getStatus()).isEqualTo(Statuses.AGENCY_DECLINED);

        // The decline reason is recorded on the agency record
        assertThat(reviewed.getDeclineReason()).isEqualTo(Statuses.DECLINE_ADVERSE_REGULATORY);
    }

    @Test
    @DisplayName("Production commitment sets the initial agency tier @v1")
    void productionCommitmentSetsTheInitialAgencyTier() {
        // An agency that projects $750,000 of new written premium in its first year
        Agencies agency = newAgency();

        // The distribution manager confirms the production commitment
        Agencies confirmed = db(() -> onboarding.confirmProductionCommitment(
                agency.getId(), new BigDecimal("750000.00")));

        // The agency is assigned the Silver tier
        assertThat(confirmed.getTier()).isEqualTo(Statuses.TIER_SILVER);

        // The tier drives the starting commission schedule: Silver earns 12% new
        // business and is contingent eligible (see the table in the spec)
        var tier = db(() -> onboarding.tierFor(new BigDecimal("750000.00")));
        assertThat(tier.getNewBusinessCommissionPct()).isEqualByComparingTo("12.00");
        assertThat(tier.getContingentEligible()).isTrue();
    }

    @Test
    @DisplayName("Production commitment sets the initial agency tier @v1 — band boundaries")
    void tierBandsFollowTheTable() {
        // The table in the spec: Bronze under $250,000, Silver $250,000 –
        // $1,000,000, Gold over $1,000,000
        assertThat(db(() -> onboarding.tierFor(new BigDecimal("249999.00"))).getTierName())
                .isEqualTo(Statuses.TIER_BRONZE);
        assertThat(db(() -> onboarding.tierFor(new BigDecimal("250000.00"))).getTierName())
                .isEqualTo(Statuses.TIER_SILVER);
        assertThat(db(() -> onboarding.tierFor(new BigDecimal("1000000.00"))).getTierName())
                .isEqualTo(Statuses.TIER_SILVER);
        assertThat(db(() -> onboarding.tierFor(new BigDecimal("1500000.00"))).getTierName())
                .isEqualTo(Statuses.TIER_GOLD);

        // Bronze is not contingent eligible
        assertThat(db(() -> onboarding.tierFor(new BigDecimal("100000.00")))
                .getContingentEligible()).isFalse();
    }
}
