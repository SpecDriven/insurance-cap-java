package com.acme.insurance.handlers;

import org.springframework.stereotype.Component;

import com.acme.insurance.claims.AdjusterAssignmentManager;
import com.acme.insurance.claims.FnolManager;
import com.acme.insurance.claims.SettlementManager;
import com.acme.insurance.domain.Statuses;
import com.sap.cds.Struct;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;

import cds.gen.claimsservice.AuthorizeRepairContext;
import cds.gen.claimsservice.ClaimsService_;
import cds.gen.claimsservice.RecordDamageEstimateContext;
import cds.gen.claimsservice.RecordRepairEstimateContext;
import cds.gen.claimsservice.ReportAccidentContext;
import cds.gen.claimsservice.ReportAccidentForInsuredContext;
import cds.gen.claimsservice.ReportWaterDamageContext;

/**
 * Spec: specs/claims/auto-accidents/record-car-accident.feature.md
 * Spec: specs/claims/auto-accidents/assign-adjuster.feature.md
 * Spec: specs/claims/auto-accidents/settle-collision-claim.feature.md
 * Spec: specs/claims/home-claims/record-water-damage-claim.feature.md
 */
@Component
@ServiceName(ClaimsService_.CDS_NAME)
public class ClaimsServiceHandler implements EventHandler {

    private final FnolManager fnol;
    private final AdjusterAssignmentManager assignment;
    private final SettlementManager settlement;

    public ClaimsServiceHandler(FnolManager fnol, AdjusterAssignmentManager assignment,
            SettlementManager settlement) {
        this.fnol = fnol;
        this.assignment = assignment;
        this.settlement = settlement;
    }

    @On(event = ReportAccidentContext.CDS_NAME)
    public void onReportAccident(ReportAccidentContext context) {
        var claim = fnol.reportAutoAccident(context.getPolicyId(), context.getDateOfLoss(),
                context.getLossLocation(), context.getDescription(), context.getPhotos(),
                Statuses.CHANNEL_ONLINE);
        context.setResult(Struct.access(claim).as(cds.gen.claimsservice.Claims.class));
        context.setCompleted();
    }

    /** Hotline FNOL: a claim is opened exactly as if it had been reported online. */
    @On(event = ReportAccidentForInsuredContext.CDS_NAME)
    public void onReportAccidentForInsured(ReportAccidentForInsuredContext context) {
        var claim = fnol.reportAutoAccident(context.getPolicyId(), context.getDateOfLoss(),
                context.getLossLocation(), context.getDescription(), context.getPhotos(),
                Statuses.CHANNEL_HOTLINE);
        context.setResult(Struct.access(claim).as(cds.gen.claimsservice.Claims.class));
        context.setCompleted();
    }

    @On(event = ReportWaterDamageContext.CDS_NAME)
    public void onReportWaterDamage(ReportWaterDamageContext context) {
        var claim = fnol.reportWaterDamage(context.getPolicyId(), context.getDateOfLoss(),
                context.getDescription(), context.getCauseOfLoss());
        context.setResult(Struct.access(claim).as(cds.gen.claimsservice.Claims.class));
        context.setCompleted();
    }

    @On(event = RecordDamageEstimateContext.CDS_NAME)
    public void onRecordDamageEstimate(RecordDamageEstimateContext context) {
        var claim = assignment.recordDamageEstimate(context.getClaimId(), context.getEstimatedDamage());
        context.setResult(Struct.access(claim).as(cds.gen.claimsservice.Claims.class));
        context.setCompleted();
    }

    @On(event = RecordRepairEstimateContext.CDS_NAME)
    public void onRecordRepairEstimate(RecordRepairEstimateContext context) {
        var claim = settlement.recordRepairEstimate(context.getClaimId(), context.getRepairShopName(),
                context.getAmount());
        context.setResult(Struct.access(claim).as(cds.gen.claimsservice.Claims.class));
        context.setCompleted();
    }

    @On(event = AuthorizeRepairContext.CDS_NAME)
    public void onAuthorizeRepair(AuthorizeRepairContext context) {
        var claim = settlement.authorizeRepair(context.getClaimId());
        context.setResult(Struct.access(claim).as(cds.gen.claimsservice.Claims.class));
        context.setCompleted();
    }
}
