package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.Inspections;
import cds.gen.insurance.LossControlSurveys;
import cds.gen.insurance.Policies;

/**
 * Spec: specs/underwriting/inspections/order-property-inspection.feature.md
 * "Duplicate order suppressed for the same address", "Inspection not returned
 * by the vendor due date", and "Rush order after a mid-term coverage increase"
 * are [proposed] and intentionally absent.
 */
@DisplayName("Order Property Inspection — specs/underwriting/inspections/order-property-inspection.feature.md")
class OrderPropertyInspectionTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Exterior inspection ordered on a new homeowners policy @v1")
    void exteriorInspectionOrderedOnANewHomeownersPolicy() {
        // A newly bound HO-3 with a dwelling limit of $480,000
        Policies policy = insertHomePolicy(p -> p.setDwellingCoverage(new BigDecimal("480000.00")));

        Inspections inspection = db(() -> inspections.order(policy.getId(),
                "Lone Star Inspections", "+1-555-0148", 0));

        // An exterior inspection is ordered from the assigned vendor within 1 business day
        assertThat(inspection.getInspectionType()).isEqualTo(Statuses.INSPECTION_TYPE_EXTERIOR);
        assertThat(inspection.getVendorName()).isEqualTo("Lone Star Inspections");
        assertThat(inspection.getOrderDueBy()).isBeforeOrEqualTo(BASE_DATE.plusDays(3));

        // The $300,000–$749,999 band carries a 21-day vendor due date
        assertThat(inspection.getVendorDueDate()).isEqualTo(BASE_DATE.plusDays(21));

        // The order includes the risk address, contact phone, and inspection type
        assertThat(inspection.getRiskAddress()).isNotBlank();
        assertThat(inspection.getContactPhone()).isEqualTo("+1-555-0148");
    }

    @Test
    @DisplayName("Interior inspection required above a value threshold @v1")
    void interiorInspectionRequiredAboveAValueThreshold() {
        // A dwelling limit above the $750,000 interior inspection threshold
        Policies policy = insertHomePolicy(p -> p.setDwellingCoverage(new BigDecimal("900000.00")));

        Inspections inspection = db(() -> inspections.order(policy.getId(),
                "Lone Star Inspections", "+1-555-0148", 0));

        // A full interior and exterior inspection is ordered
        assertThat(inspection.getInspectionType()).isEqualTo(Statuses.INSPECTION_TYPE_INTERIOR);
        assertThat(inspection.getVendorDueDate()).isEqualTo(BASE_DATE.plusDays(30));

        // An appointment is requested
        assertThat(inspection.getAppointmentRequested()).isTrue();

        // The insured is notified that access to the interior is required
        assertThat(inspection.getInteriorAccessRequired()).isTrue();
        assertThat(emailSubjects()).contains("Interior inspection appointment required");
    }

    @Test
    @DisplayName("Inspection ordered on a vacant dwelling @v1")
    void inspectionOrderedOnAVacantDwelling() {
        // A risk reported as unoccupied for more than 60 days
        Policies policy = insertHomePolicy(p -> p.setDwellingCoverage(new BigDecimal("480000.00")));

        Inspections inspection = db(() -> inspections.order(policy.getId(),
                "Lone Star Inspections", "+1-555-0148", 75));

        // The vacancy is noted on the order so the vendor documents securement
        assertThat(inspection.getVacancyNoted()).isTrue();

        // A loss control survey is also opened
        assertThat(db(() -> persistence.run(com.sap.cds.ql.Select
                .from(cds.gen.insurance.LossControlSurveys_.class)
                .where(s -> s.policy_ID().eq(policy.getId())))
                .listOf(LossControlSurveys.class))).hasSize(1);
    }

    @Test
    @DisplayName("Vendor cannot gain access after three attempts @v1")
    void vendorCannotGainAccessAfterThreeAttempts() {
        Policies policy = insertHomePolicy(p -> p.setDwellingCoverage(new BigDecimal("480000.00")));
        Inspections inspection = db(() -> inspections.order(policy.getId(),
                "Lone Star Inspections", "+1-555-0148", 0));
        emails.reset();

        // A vendor documents three failed access attempts over 14 days
        Inspections noAccess = db(() -> inspections.recordNoAccess(inspection.getId(), 3));

        // The policy is flagged for non-renewal for refusal to permit inspection
        assertThat(noAccess.getStatus()).isEqualTo(Statuses.INSPECTION_NO_ACCESS);
        assertThat(noAccess.getAccessAttempts()).isEqualTo(3);

        // The insured receives a final access request with a 10-day deadline
        assertThat(emailSubjects()).contains("Final request for inspection access");
        assertThat(emails.sent().stream().map(m -> m.body()))
                .anySatisfy(body -> assertThat(body)
                        .contains("10 days")
                        .contains(Statuses.NON_RENEWAL_REFUSED_INSPECTION));
    }
}
