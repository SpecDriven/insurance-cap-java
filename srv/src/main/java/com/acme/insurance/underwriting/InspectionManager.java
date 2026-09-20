package com.acme.insurance.underwriting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.BusinessHours;
import com.acme.insurance.domain.Statuses;
import com.acme.insurance.ports.EmailMessage;
import com.acme.insurance.ports.EmailPort;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.InspectionThresholds;
import cds.gen.insurance.InspectionThresholds_;
import cds.gen.insurance.Inspections;
import cds.gen.insurance.Inspections_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;
import cds.gen.insurance.Submissions;

/**
 * Orders exterior, interior, and engineering inspections from third-party
 * vendors and tracks them to completion.
 *
 * Spec: specs/underwriting/inspections/order-property-inspection.feature.md
 * The dwelling-limit bands are CSV seed data in
 * db/data/insurance-InspectionThresholds.csv.
 */
@Component
public class InspectionManager {

    private final PersistenceService persistence;
    private final LossControlSurveyManager surveys;
    private final EmailPort email;
    private final Clock clock;

    public InspectionManager(PersistenceService persistence, LossControlSurveyManager surveys,
            EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.surveys = surveys;
        this.email = email;
        this.clock = clock;
    }

    /**
     * "Exterior inspection ordered on a new homeowners policy" — an HO-3 with a
     * $480,000 dwelling limit is ordered from the assigned vendor within 1
     * business day, and the order carries the risk address, contact phone, and
     * inspection type.
     *
     * "Interior inspection required above a value threshold" — above $750,000 a
     * full interior and exterior inspection is ordered, an appointment is
     * requested, and the insured is notified interior access is required.
     *
     * "Inspection ordered on a vacant dwelling" — a risk unoccupied more than 60
     * days has the vacancy noted so the vendor documents securement, and a loss
     * control survey is also opened.
     */
    public Inspections order(String policyId, String vendorName, String contactPhone,
            Integer unoccupiedDays) {
        Policies policy = loadPolicy(policyId);
        LocalDate today = LocalDate.now(clock);

        BigDecimal dwellingLimit = policy.getDwellingCoverage() == null
                ? BigDecimal.ZERO : policy.getDwellingCoverage();
        InspectionThresholds threshold = thresholdFor(dwellingLimit);

        if (Statuses.INSPECTION_TYPE_NONE.equals(threshold.getInspectionType())) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "No inspection required below the $300,000 threshold");
        }

        boolean vacant = unoccupiedDays != null
                && unoccupiedDays > Statuses.VACANCY_DAYS_THRESHOLD;
        boolean interiorRequired =
                Statuses.INSPECTION_TYPE_INTERIOR.equals(threshold.getInspectionType())
                || Statuses.INSPECTION_TYPE_ENGINEER.equals(threshold.getInspectionType());

        Inspections inspection = Inspections.create();
        inspection.setId(UUID.randomUUID().toString());
        inspection.setPolicyId(policyId);
        inspection.setInspectionType(threshold.getInspectionType());
        inspection.setStatus(Statuses.INSPECTION_ORDERED);
        inspection.setVendorName(vendorName);
        inspection.setOrderedOn(today);
        // Ordered from the assigned vendor within 1 business day.
        inspection.setOrderDueBy(BusinessHours
                .plusBusinessHours(today.atTime(BusinessHours.DAY_START), BusinessHours.HOURS_PER_DAY)
                .toLocalDate());
        inspection.setVendorDueDate(today.plusDays(threshold.getVendorDueDays()));
        inspection.setRiskAddress(policy.getGaragingZip());
        inspection.setContactPhone(contactPhone);
        inspection.setAppointmentRequested(interiorRequired);
        inspection.setInteriorAccessRequired(interiorRequired);
        inspection.setVacancyNoted(vacant);
        inspection.setAccessAttempts(0);
        persistence.run(Insert.into(Inspections_.class).entry(inspection));

        if (interiorRequired) {
            email.send(new EmailMessage(policy.getNamedInsuredEmail(),
                    "Interior inspection appointment required",
                    "Access to the interior of the dwelling is required to complete the "
                            + "inspection. The vendor will contact you to arrange an appointment."));
        }

        // A vacant dwelling also opens a loss control survey.
        if (vacant) {
            surveys.orderForVacancy(policy);
        }

        return load(inspection.getId());
    }

    /** Inspection ordered as a condition of an underwriter's accept decision. */
    Inspections orderForSubmission(Submissions submission, Integer dueInDays) {
        LocalDate today = LocalDate.now(clock);
        BigDecimal dwellingLimit = submission.getDwellingCoverage() == null
                ? BigDecimal.ZERO : submission.getDwellingCoverage();
        InspectionThresholds threshold = thresholdFor(dwellingLimit);

        Inspections inspection = Inspections.create();
        inspection.setId(UUID.randomUUID().toString());
        inspection.setSubmissionId(submission.getId());
        inspection.setInspectionType(Statuses.INSPECTION_TYPE_NONE
                .equals(threshold.getInspectionType())
                        ? Statuses.INSPECTION_TYPE_EXTERIOR : threshold.getInspectionType());
        inspection.setStatus(Statuses.INSPECTION_ORDERED);
        inspection.setOrderedOn(today);
        inspection.setVendorDueDate(today.plusDays(dueInDays == null
                ? Statuses.SURVEY_DUE_DAYS : dueInDays));
        inspection.setRiskAddress(submission.getRiskAddress());
        inspection.setAccessAttempts(0);
        persistence.run(Insert.into(Inspections_.class).entry(inspection));
        return load(inspection.getId());
    }

    /**
     * "Vendor cannot gain access after three attempts" — the policy is flagged
     * for non-renewal for refusal to permit inspection, and the insured receives
     * a final access request with a 10-day deadline.
     */
    public Inspections recordNoAccess(String inspectionId, Integer attempts) {
        Inspections inspection = load(inspectionId);
        inspection.setAccessAttempts(attempts);

        if (attempts != null && attempts >= Statuses.NO_ACCESS_ATTEMPTS) {
            inspection.setStatus(Statuses.INSPECTION_NO_ACCESS);
            persistence.run(Update.entity(Inspections_.class).entry(inspection));

            Policies policy = loadPolicy(inspection.getPolicyId());
            email.send(new EmailMessage(policy.getNamedInsuredEmail(),
                    "Final request for inspection access",
                    "The inspection vendor documented " + attempts + " failed access attempts. "
                            + "Please contact us within " + Statuses.FINAL_ACCESS_DEADLINE_DAYS
                            + " days. The policy is flagged for non-renewal for "
                            + Statuses.NON_RENEWAL_REFUSED_INSPECTION + "."));
        } else {
            persistence.run(Update.entity(Inspections_.class).entry(inspection));
        }
        return load(inspectionId);
    }

    /** The inspection band for a dwelling limit, from the CSV threshold table. */
    public InspectionThresholds thresholdFor(BigDecimal dwellingLimit) {
        List<InspectionThresholds> thresholds = persistence
                .run(Select.from(InspectionThresholds_.class))
                .listOf(InspectionThresholds.class);
        return thresholds.stream()
                .filter(t -> dwellingLimit.compareTo(t.getMinDwellingLimit()) >= 0
                        && dwellingLimit.compareTo(t.getMaxDwellingLimit()) <= 0)
                .findFirst()
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "No inspection threshold for dwelling limit " + dwellingLimit));
    }

    public Inspections load(String inspectionId) {
        return persistence.run(Select.from(Inspections_.class)
                .where(i -> i.ID().eq(inspectionId)))
                .first(Inspections.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Inspection not found: " + inspectionId));
    }

    private Policies loadPolicy(String policyId) {
        return persistence.run(Select.from(Policies_.class)
                .where(p -> p.ID().eq(policyId)))
                .first(Policies.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Policy not found: " + policyId));
    }
}
