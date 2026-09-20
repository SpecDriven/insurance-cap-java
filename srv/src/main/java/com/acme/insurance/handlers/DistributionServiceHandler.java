package com.acme.insurance.handlers;

import java.util.List;

import org.springframework.stereotype.Component;

import com.acme.insurance.distribution.AgencyOnboardingManager;
import com.acme.insurance.distribution.AppointmentManager;
import com.acme.insurance.distribution.BookOfBusinessManager;
import com.acme.insurance.distribution.CommissionManager;
import com.acme.insurance.distribution.EmbeddedOfferManager;
import com.acme.insurance.distribution.LicenseRenewalManager;
import com.acme.insurance.distribution.PartnerIntegrationManager;
import com.acme.insurance.distribution.PortalAccessManager;
import com.acme.insurance.distribution.PortalQuotingManager;
import com.acme.insurance.distribution.PortalReferralManager;
import com.acme.insurance.distribution.ProducerLicensingManager;
import com.acme.insurance.distribution.ProductionBlockManager;
import com.acme.insurance.distribution.TerminationManager;
import com.sap.cds.Struct;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;

import cds.gen.distributionservice.AcceptEmbeddedOfferContext;
import cds.gen.distributionservice.AcknowledgeAppointmentFilingContext;
import cds.gen.distributionservice.AcknowledgeDisclosuresContext;
import cds.gen.distributionservice.Agencies;
import cds.gen.distributionservice.AgencyAppointments;
import cds.gen.distributionservice.AppointAgencyContext;
import cds.gen.distributionservice.ApproveComplianceOverrideContext;
import cds.gen.distributionservice.AttemptBindContext;
import cds.gen.distributionservice.BindFromPartnerTokenContext;
import cds.gen.distributionservice.BindOnBehalfOfStaffContext;
import cds.gen.distributionservice.BookExports;
import cds.gen.distributionservice.BookOfBusinessViews;
import cds.gen.distributionservice.BookRolls;
import cds.gen.distributionservice.CalculateContingentCommissionContext;
import cds.gen.distributionservice.CertifyPartnerContext;
import cds.gen.distributionservice.ChargebackCommissionContext;
import cds.gen.distributionservice.CheckLicenseForRiskContext;
import cds.gen.distributionservice.CommissionEntries;
import cds.gen.distributionservice.CommissionSchedules;
import cds.gen.distributionservice.CommissionStatements;
import cds.gen.distributionservice.CompleteRegulatoryReviewContext;
import cds.gen.distributionservice.ComplianceOverrides;
import cds.gen.distributionservice.ConfirmProductionCommitmentContext;
import cds.gen.distributionservice.ContingentCommissions;
import cds.gen.distributionservice.DeactivatePortalUserContext;
import cds.gen.distributionservice.DeclinePortalReferralContext;
import cds.gen.distributionservice.DesignateReplacementAgencyContext;
import cds.gen.distributionservice.DisableDormantAccountsContext;
import cds.gen.distributionservice.DistributionService_;
import cds.gen.distributionservice.EarnNewBusinessCommissionContext;
import cds.gen.distributionservice.EmbeddedOffers;
import cds.gen.distributionservice.ExportBookOfBusinessContext;
import cds.gen.distributionservice.HandOffToUnderwriterContext;
import cds.gen.distributionservice.InviteProducerContext;
import cds.gen.distributionservice.LapseExpiredAppointmentsContext;
import cds.gen.distributionservice.LicenseReminders;
import cds.gen.distributionservice.PartnerCredentials;
import cds.gen.distributionservice.Partners;
import cds.gen.distributionservice.Policies;
import cds.gen.distributionservice.PortalQuotes;
import cds.gen.distributionservice.PortalReferrals;
import cds.gen.distributionservice.PortalUsers;
import cds.gen.distributionservice.ProducerLicenses;
import cds.gen.distributionservice.Producers;
import cds.gen.distributionservice.ProduceFinalStatementContext;
import cds.gen.distributionservice.ProductionBlocks;
import cds.gen.distributionservice.PublishApiVersionContext;
import cds.gen.distributionservice.PublishCommissionScheduleContext;
import cds.gen.distributionservice.ReattributeSaleContext;
import cds.gen.distributionservice.RecordAgentOfRecordLetterContext;
import cds.gen.distributionservice.RecordLicenseRenewalContext;
import cds.gen.distributionservice.RecordLicenseSuspensionContext;
import cds.gen.distributionservice.RecordPartnerRequestRateContext;
import cds.gen.distributionservice.RegisterProducerContext;
import cds.gen.distributionservice.ReinstateLicenseContext;
import cds.gen.distributionservice.RemoveAdministratorRoleContext;
import cds.gen.distributionservice.RequestLossRunsContext;
import cds.gen.distributionservice.RotatePartnerCredentialContext;
import cds.gen.distributionservice.RunPortalReferralSlaMonitorContext;
import cds.gen.distributionservice.RunRenewalTrackerContext;
import cds.gen.distributionservice.SaleAttributions;
import cds.gen.distributionservice.StartAutoQuoteInPortalContext;
import cds.gen.distributionservice.StartHomeQuoteInPortalContext;
import cds.gen.distributionservice.SubmitOnboardingPacketContext;
import cds.gen.distributionservice.TerminateAgencyContext;
import cds.gen.distributionservice.UploadLossRunsContext;
import cds.gen.distributionservice.VerifyProducerLicenseContext;
import cds.gen.distributionservice.ViewBookOfBusinessContext;
import cds.gen.distributionservice.ViewRenewalPipelineContext;
import cds.gen.distributionservice.OfferEmbeddedRentersQuoteContext;

/**
 * OData actions for the distribution domain.
 *
 * Specs: specs/distribution/**.feature.md — only [published] scenarios are
 * exposed; [proposed] behavior is intentionally absent (ARCHITECTURE.md
 * "Spec traceability").
 */
@Component
@ServiceName(DistributionService_.CDS_NAME)
public class DistributionServiceHandler implements EventHandler {

    private final AgencyOnboardingManager onboarding;
    private final AppointmentManager appointments;
    private final CommissionManager commissions;
    private final TerminationManager terminations;
    private final ProducerLicensingManager licensing;
    private final LicenseRenewalManager renewals;
    private final ProductionBlockManager blocks;
    private final PortalAccessManager portalAccess;
    private final PortalQuotingManager portalQuoting;
    private final PortalReferralManager portalReferrals;
    private final BookOfBusinessManager book;
    private final PartnerIntegrationManager partners;
    private final EmbeddedOfferManager offers;

    public DistributionServiceHandler(AgencyOnboardingManager onboarding,
            AppointmentManager appointments, CommissionManager commissions,
            TerminationManager terminations, ProducerLicensingManager licensing,
            LicenseRenewalManager renewals, ProductionBlockManager blocks,
            PortalAccessManager portalAccess, PortalQuotingManager portalQuoting,
            PortalReferralManager portalReferrals, BookOfBusinessManager book,
            PartnerIntegrationManager partners, EmbeddedOfferManager offers) {
        this.onboarding = onboarding;
        this.appointments = appointments;
        this.commissions = commissions;
        this.terminations = terminations;
        this.licensing = licensing;
        this.renewals = renewals;
        this.blocks = blocks;
        this.portalAccess = portalAccess;
        this.portalQuoting = portalQuoting;
        this.portalReferrals = portalReferrals;
        this.book = book;
        this.partners = partners;
        this.offers = offers;
    }

    // ─── Agency onboarding and appointment ───

    @On(event = SubmitOnboardingPacketContext.CDS_NAME)
    public void onSubmitOnboardingPacket(SubmitOnboardingPacketContext context) {
        var agency = onboarding.submitPacket(context.getLegalName(), context.getTaxId(),
                context.getDomicileState(), context.getResidentLicenseActive(),
                context.getEoPerClaimLimit(), context.getEoAggregateLimit(),
                context.getW9Signed(), context.getPrincipalName(), context.getPrincipalEmail());
        context.setResult(Struct.access(agency).as(Agencies.class));
        context.setCompleted();
    }

    @On(event = CompleteRegulatoryReviewContext.CDS_NAME)
    public void onCompleteRegulatoryReview(CompleteRegulatoryReviewContext context) {
        var agency = onboarding.completeRegulatoryReview(context.getAgencyId(),
                context.getPriorLicenseRevocation());
        context.setResult(Struct.access(agency).as(Agencies.class));
        context.setCompleted();
    }

    @On(event = ConfirmProductionCommitmentContext.CDS_NAME)
    public void onConfirmProductionCommitment(ConfirmProductionCommitmentContext context) {
        var agency = onboarding.confirmProductionCommitment(context.getAgencyId(),
                context.getProjectedNewPremium());
        context.setResult(Struct.access(agency).as(Agencies.class));
        context.setCompleted();
    }

    @On(event = AppointAgencyContext.CDS_NAME)
    public void onAppointAgency(AppointAgencyContext context) {
        List<AgencyAppointments> created = appointments
                .appoint(context.getAgencyId(), context.getState(), context.getLines()).stream()
                .map(a -> Struct.access(a).as(AgencyAppointments.class))
                .toList();
        context.setResult(created);
        context.setCompleted();
    }

    @On(event = AcknowledgeAppointmentFilingContext.CDS_NAME)
    public void onAcknowledgeAppointmentFiling(AcknowledgeAppointmentFilingContext context) {
        var appointment = appointments.acknowledge(context.getAppointmentId(),
                context.getAcknowledgedOn());
        context.setResult(Struct.access(appointment).as(AgencyAppointments.class));
        context.setCompleted();
    }

    @On(event = LapseExpiredAppointmentsContext.CDS_NAME)
    public void onLapseExpiredAppointments(LapseExpiredAppointmentsContext context) {
        List<AgencyAppointments> lapsed = appointments.lapseExpired().stream()
                .map(a -> Struct.access(a).as(AgencyAppointments.class))
                .toList();
        context.setResult(lapsed);
        context.setCompleted();
    }

    // ─── Commission ───

    @On(event = PublishCommissionScheduleContext.CDS_NAME)
    public void onPublishCommissionSchedule(PublishCommissionScheduleContext context) {
        List<CommissionSchedules> published = commissions.publishSchedule(context.getAgencyId(),
                context.getPublishedBy(), context.getEffectiveDate()).stream()
                .map(s -> Struct.access(s).as(CommissionSchedules.class))
                .toList();
        context.setResult(published);
        context.setCompleted();
    }

    @On(event = EarnNewBusinessCommissionContext.CDS_NAME)
    public void onEarnNewBusinessCommission(EarnNewBusinessCommissionContext context) {
        List<CommissionEntries> entries = commissions.earnNewBusiness(context.getAgencyId(),
                context.getPolicyId(), context.getLineOfBusiness(), context.getBasisPremium(),
                context.getSharedWithAgencyId(), context.getWritingSharePct()).stream()
                .map(e -> Struct.access(e).as(CommissionEntries.class))
                .toList();
        context.setResult(entries);
        context.setCompleted();
    }

    @On(event = ChargebackCommissionContext.CDS_NAME)
    public void onChargebackCommission(ChargebackCommissionContext context) {
        List<CommissionEntries> entries = commissions.chargeback(context.getPolicyId(),
                context.getCancellationType(), context.getUnearnedPct()).stream()
                .map(e -> Struct.access(e).as(CommissionEntries.class))
                .toList();
        context.setResult(entries);
        context.setCompleted();
    }

    @On(event = CalculateContingentCommissionContext.CDS_NAME)
    public void onCalculateContingentCommission(CalculateContingentCommissionContext context) {
        var contingent = commissions.calculateContingent(context.getAgencyId(),
                context.getContingentYear(), context.getEarnedPremium(),
                context.getIncurredLoss());
        context.setResult(Struct.access(contingent).as(ContingentCommissions.class));
        context.setCompleted();
    }

    // ─── Termination ───

    @On(event = TerminateAgencyContext.CDS_NAME)
    public void onTerminateAgency(TerminateAgencyContext context) {
        var agency = terminations.terminate(context.getAgencyId(), context.getForCause(),
                context.getNoticeDate(), context.getNoticePeriodDays());
        context.setResult(Struct.access(agency).as(Agencies.class));
        context.setCompleted();
    }

    @On(event = DesignateReplacementAgencyContext.CDS_NAME)
    public void onDesignateReplacementAgency(DesignateReplacementAgencyContext context) {
        List<BookRolls> rolls = terminations.designateReplacement(
                context.getTerminatedAgencyId(), context.getReplacementAgencyId()).stream()
                .map(r -> Struct.access(r).as(BookRolls.class))
                .toList();
        context.setResult(rolls);
        context.setCompleted();
    }

    @On(event = RecordAgentOfRecordLetterContext.CDS_NAME)
    public void onRecordAgentOfRecordLetter(RecordAgentOfRecordLetterContext context) {
        var roll = terminations.recordAgentOfRecordLetter(context.getPolicyId(),
                context.getElectedAgencyId(), context.getReceivedOn());
        context.setResult(Struct.access(roll).as(BookRolls.class));
        context.setCompleted();
    }

    @On(event = ProduceFinalStatementContext.CDS_NAME)
    public void onProduceFinalStatement(ProduceFinalStatementContext context) {
        var statement = terminations.produceFinalStatement(context.getAgencyId(),
                context.getPeriodStart(), context.getPeriodEnd());
        context.setResult(Struct.access(statement).as(CommissionStatements.class));
        context.setCompleted();
    }

    // ─── Producer licensing ───

    @On(event = RegisterProducerContext.CDS_NAME)
    public void onRegisterProducer(RegisterProducerContext context) {
        var producer = licensing.register(context.getNpn(), context.getFullName(),
                context.getEmail(), context.getAgencyId(), context.getResidentState(),
                context.getRole());
        context.setResult(Struct.access(producer).as(Producers.class));
        context.setCompleted();
    }

    @On(event = VerifyProducerLicenseContext.CDS_NAME)
    public void onVerifyProducerLicense(VerifyProducerLicenseContext context) {
        var license = licensing.verify(context.getProducerId(), context.getState(),
                context.getRegistryName(), context.getLicenseClass(), context.getExpiresOn());
        context.setResult(Struct.access(license).as(ProducerLicenses.class));
        context.setCompleted();
    }

    @On(event = CheckLicenseForRiskContext.CDS_NAME)
    public void onCheckLicenseForRisk(CheckLicenseForRiskContext context) {
        var license = licensing.checkForRisk(context.getProducerId(), context.getRiskState());
        context.setResult(Struct.access(license).as(ProducerLicenses.class));
        context.setCompleted();
    }

    @On(event = RecordLicenseSuspensionContext.CDS_NAME)
    public void onRecordLicenseSuspension(RecordLicenseSuspensionContext context) {
        var license = licensing.recordSuspension(context.getProducerId(), context.getState(),
                context.getSuspendedOn());
        context.setResult(Struct.access(license).as(ProducerLicenses.class));
        context.setCompleted();
    }

    @On(event = RunRenewalTrackerContext.CDS_NAME)
    public void onRunRenewalTracker(RunRenewalTrackerContext context) {
        List<LicenseReminders> sent = renewals.runTracker().stream()
                .map(r -> Struct.access(r).as(LicenseReminders.class))
                .toList();
        context.setResult(sent);
        context.setCompleted();
    }

    @On(event = ReinstateLicenseContext.CDS_NAME)
    public void onReinstateLicense(ReinstateLicenseContext context) {
        var license = renewals.reinstate(context.getLicenseId(), context.getRenewedOn());
        context.setResult(Struct.access(license).as(ProducerLicenses.class));
        context.setCompleted();
    }

    @On(event = RecordLicenseRenewalContext.CDS_NAME)
    public void onRecordLicenseRenewal(RecordLicenseRenewalContext context) {
        var license = renewals.recordRenewal(context.getLicenseId(), context.getNewExpiresOn());
        context.setResult(Struct.access(license).as(ProducerLicenses.class));
        context.setCompleted();
    }

    // ─── Blocking unlicensed production ───

    @On(event = AttemptBindContext.CDS_NAME)
    public void onAttemptBind(AttemptBindContext context) {
        var attempt = blocks.attemptBind(context.getProducerId(), context.getRiskState(),
                context.getPolicyId());
        context.setResult(Struct.access(attempt).as(ProductionBlocks.class));
        context.setCompleted();
    }

    @On(event = BindOnBehalfOfStaffContext.CDS_NAME)
    public void onBindOnBehalfOfStaff(BindOnBehalfOfStaffContext context) {
        var attempt = blocks.bindOnBehalfOfStaff(context.getPrincipalProducerId(),
                context.getRiskState(), context.getPolicyId());
        context.setResult(Struct.access(attempt).as(ProductionBlocks.class));
        context.setCompleted();
    }

    @On(event = ApproveComplianceOverrideContext.CDS_NAME)
    public void onApproveComplianceOverride(ApproveComplianceOverrideContext context) {
        var override = blocks.approveOverride(context.getProducerId(), context.getApprovedBy(),
                context.getEvidence());
        context.setResult(Struct.access(override).as(ComplianceOverrides.class));
        context.setCompleted();
    }

    // ─── Portal user access ───

    @On(event = InviteProducerContext.CDS_NAME)
    public void onInviteProducer(InviteProducerContext context) {
        var user = portalAccess.inviteProducer(context.getAgencyId(), context.getNpn(),
                context.getFullName(), context.getEmail());
        context.setResult(Struct.access(user).as(PortalUsers.class));
        context.setCompleted();
    }

    @On(event = DeactivatePortalUserContext.CDS_NAME)
    public void onDeactivatePortalUser(DeactivatePortalUserContext context) {
        var user = portalAccess.deactivate(context.getPortalUserId(),
                context.getReceivingProducerId());
        context.setResult(Struct.access(user).as(PortalUsers.class));
        context.setCompleted();
    }

    @On(event = RemoveAdministratorRoleContext.CDS_NAME)
    public void onRemoveAdministratorRole(RemoveAdministratorRoleContext context) {
        var user = portalAccess.removeAdministratorRole(context.getPortalUserId());
        context.setResult(Struct.access(user).as(PortalUsers.class));
        context.setCompleted();
    }

    @On(event = DisableDormantAccountsContext.CDS_NAME)
    public void onDisableDormantAccounts(DisableDormantAccountsContext context) {
        List<PortalUsers> disabled = portalAccess.disableDormantAccounts().stream()
                .map(u -> Struct.access(u).as(PortalUsers.class))
                .toList();
        context.setResult(disabled);
        context.setCompleted();
    }

    // ─── Portal quoting ───

    @On(event = StartAutoQuoteInPortalContext.CDS_NAME)
    public void onStartAutoQuoteInPortal(StartAutoQuoteInPortalContext context) {
        var quote = portalQuoting.startAutoQuote(context.getProducerId(),
                context.getVehicleMake(), context.getVehicleModel(), context.getVehicleYear(),
                context.getDriverName(), context.getDriverEmail(), context.getDriverPhone(),
                context.getGaragingState(), context.getGaragingZip(), context.getMvrConsent());
        context.setResult(Struct.access(quote).as(PortalQuotes.class));
        context.setCompleted();
    }

    @On(event = StartHomeQuoteInPortalContext.CDS_NAME)
    public void onStartHomeQuoteInPortal(StartHomeQuoteInPortalContext context) {
        var quote = portalQuoting.startHomeQuote(context.getProducerId(), context.getAddress(),
                context.getYearBuilt(), context.getConstructionType(), context.getOwnerName(),
                context.getOwnerEmail(), context.getDwellingCoverage(),
                context.getGaragingState(), context.getRoofAgeYears());
        context.setResult(Struct.access(quote).as(PortalQuotes.class));
        context.setCompleted();
    }

    // ─── Portal hand-off ───

    @On(event = HandOffToUnderwriterContext.CDS_NAME)
    public void onHandOffToUnderwriter(HandOffToUnderwriterContext context) {
        var referral = portalReferrals.handOff(context.getPortalQuoteId(),
                context.getProducerId(), context.getAnnualPremium(), context.getClassCode());
        context.setResult(Struct.access(referral).as(PortalReferrals.class));
        context.setCompleted();
    }

    @On(event = RequestLossRunsContext.CDS_NAME)
    public void onRequestLossRuns(RequestLossRunsContext context) {
        var referral = portalReferrals.requestLossRuns(context.getPortalReferralId(),
                context.getYearsRequested());
        context.setResult(Struct.access(referral).as(PortalReferrals.class));
        context.setCompleted();
    }

    @On(event = UploadLossRunsContext.CDS_NAME)
    public void onUploadLossRuns(UploadLossRunsContext context) {
        var referral = portalReferrals.uploadLossRuns(context.getPortalReferralId());
        context.setResult(Struct.access(referral).as(PortalReferrals.class));
        context.setCompleted();
    }

    @On(event = DeclinePortalReferralContext.CDS_NAME)
    public void onDeclinePortalReferral(DeclinePortalReferralContext context) {
        var referral = portalReferrals.decline(context.getPortalReferralId(),
                context.getDeclineReason());
        context.setResult(Struct.access(referral).as(PortalReferrals.class));
        context.setCompleted();
    }

    @On(event = RunPortalReferralSlaMonitorContext.CDS_NAME)
    public void onRunPortalReferralSlaMonitor(RunPortalReferralSlaMonitorContext context) {
        List<PortalReferrals> escalated = portalReferrals.runSlaMonitor().stream()
                .map(r -> Struct.access(r).as(PortalReferrals.class))
                .toList();
        context.setResult(escalated);
        context.setCompleted();
    }

    // ─── Book of business ───

    @On(event = ViewBookOfBusinessContext.CDS_NAME)
    public void onViewBookOfBusiness(ViewBookOfBusinessContext context) {
        var view = book.view(context.getProducerId(), context.getAgencyWide());
        context.setResult(Struct.access(view).as(BookOfBusinessViews.class));
        context.setCompleted();
    }

    @On(event = ExportBookOfBusinessContext.CDS_NAME)
    public void onExportBookOfBusiness(ExportBookOfBusinessContext context) {
        var export = book.export(context.getProducerId());
        context.setResult(Struct.access(export).as(BookExports.class));
        context.setCompleted();
    }

    @On(event = ViewRenewalPipelineContext.CDS_NAME)
    public void onViewRenewalPipeline(ViewRenewalPipelineContext context) {
        List<Policies> pipeline = book
                .renewalPipeline(context.getAgencyId(), context.getWithinDays()).stream()
                .map(p -> Struct.access(p).as(Policies.class))
                .toList();
        context.setResult(pipeline);
        context.setCompleted();
    }

    // ─── Embedded partners ───

    @On(event = CertifyPartnerContext.CDS_NAME)
    public void onCertifyPartner(CertifyPartnerContext context) {
        var partner = partners.certify(context.getPartnerId(), context.getScopedLines(),
                context.getReviewedBy());
        context.setResult(Struct.access(partner).as(Partners.class));
        context.setCompleted();
    }

    @On(event = RotatePartnerCredentialContext.CDS_NAME)
    public void onRotatePartnerCredential(RotatePartnerCredentialContext context) {
        List<PartnerCredentials> keys = partners
                .rotateCredential(context.getPartnerId()).stream()
                .map(c -> Struct.access(c).as(PartnerCredentials.class))
                .toList();
        context.setResult(keys);
        context.setCompleted();
    }

    @On(event = RecordPartnerRequestRateContext.CDS_NAME)
    public void onRecordPartnerRequestRate(RecordPartnerRequestRateContext context) {
        var partner = partners.recordRequestRate(context.getPartnerId(),
                context.getRequestsPerMinute(), context.getSustainedMinutes());
        context.setResult(Struct.access(partner).as(Partners.class));
        context.setCompleted();
    }

    @On(event = PublishApiVersionContext.CDS_NAME)
    public void onPublishApiVersion(PublishApiVersionContext context) {
        var partner = partners.publishApiVersion(context.getNewVersion(),
                context.getPublishedOn());
        context.setResult(partner == null ? null : Struct.access(partner).as(Partners.class));
        context.setCompleted();
    }

    @On(event = OfferEmbeddedRentersQuoteContext.CDS_NAME)
    public void onOfferEmbeddedRentersQuote(OfferEmbeddedRentersQuoteContext context) {
        var offer = offers.offerRenters(context.getPartnerCode(), context.getApplicantName(),
                context.getUnitAddress(), context.getLeaseStartDate());
        context.setResult(Struct.access(offer).as(EmbeddedOffers.class));
        context.setCompleted();
    }

    @On(event = AcknowledgeDisclosuresContext.CDS_NAME)
    public void onAcknowledgeDisclosures(AcknowledgeDisclosuresContext context) {
        var offer = offers.acknowledgeDisclosures(context.getOfferId(),
                context.getDisclosureShown(), context.getCoverageSummaryShown());
        context.setResult(Struct.access(offer).as(EmbeddedOffers.class));
        context.setCompleted();
    }

    @On(event = AcceptEmbeddedOfferContext.CDS_NAME)
    public void onAcceptEmbeddedOffer(AcceptEmbeddedOfferContext context) {
        var offer = offers.accept(context.getOfferId());
        context.setResult(Struct.access(offer).as(EmbeddedOffers.class));
        context.setCompleted();
    }

    @On(event = BindFromPartnerTokenContext.CDS_NAME)
    public void onBindFromPartnerToken(BindFromPartnerTokenContext context) {
        var attribution = offers.bindFromToken(context.getQuoteToken(), context.getPolicyId());
        context.setResult(Struct.access(attribution).as(SaleAttributions.class));
        context.setCompleted();
    }

    @On(event = ReattributeSaleContext.CDS_NAME)
    public void onReattributeSale(ReattributeSaleContext context) {
        List<SaleAttributions> result = offers.reattribute(context.getPolicyId(),
                context.getNewPartnerCode(), context.getApprovedBy()).stream()
                .map(a -> Struct.access(a).as(SaleAttributions.class))
                .toList();
        context.setResult(result);
        context.setCompleted();
    }
}
