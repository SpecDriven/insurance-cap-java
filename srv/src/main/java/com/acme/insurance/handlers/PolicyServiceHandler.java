package com.acme.insurance.handlers;

import org.springframework.stereotype.Component;

import com.acme.insurance.policy.BindingManager;
import com.acme.insurance.policy.CancellationManager;
import com.acme.insurance.policy.EndorsementManager;
import com.sap.cds.Struct;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;

import cds.gen.policyservice.AddVehicleContext;
import cds.gen.policyservice.BindPolicyContext;
import cds.gen.policyservice.IssueCancellationNoticeContext;
import cds.gen.policyservice.PolicyService_;
import cds.gen.policyservice.RequestCancellationContext;

/**
 * Spec: specs/policy/auto-policy/bind-policy.feature.md
 * Spec: specs/policy/auto-policy/cancel-policy.feature.md
 * Spec: specs/policy/auto-policy/endorse-policy.feature.md
 */
@Component
@ServiceName(PolicyService_.CDS_NAME)
public class PolicyServiceHandler implements EventHandler {

    private final BindingManager binding;
    private final CancellationManager cancellation;
    private final EndorsementManager endorsement;

    public PolicyServiceHandler(BindingManager binding, CancellationManager cancellation,
            EndorsementManager endorsement) {
        this.binding = binding;
        this.cancellation = cancellation;
        this.endorsement = endorsement;
    }

    @On(event = BindPolicyContext.CDS_NAME)
    public void onBindPolicy(BindPolicyContext context) {
        var policy = binding.bind(context.getQuoteId(), context.getPlan(),
                context.getRequestedStartDate(), context.getBillingPlan(),
                context.getPaymentMethodToken());
        context.setResult(Struct.access(policy).as(cds.gen.policyservice.Policies.class));
        context.setCompleted();
    }

    @On(event = RequestCancellationContext.CDS_NAME)
    public void onRequestCancellation(RequestCancellationContext context) {
        var policy = cancellation.requestCancellation(context.getPolicyId(),
                context.getCancellationDate());
        context.setResult(Struct.access(policy).as(cds.gen.policyservice.Policies.class));
        context.setCompleted();
    }

    @On(event = AddVehicleContext.CDS_NAME)
    public void onAddVehicle(AddVehicleContext context) {
        var policy = endorsement.addVehicle(context.getPolicyId(), context.getMake(),
                context.getModel_(), context.getModelYear());
        context.setResult(Struct.access(policy).as(cds.gen.policyservice.Policies.class));
        context.setCompleted();
    }

    @On(event = IssueCancellationNoticeContext.CDS_NAME)
    public void onIssueCancellationNotice(IssueCancellationNoticeContext context) {
        var notice = cancellation.issueNotice(context.getPolicyId());
        context.setResult(Struct.access(notice).as(cds.gen.policyservice.CancellationNotices.class));
        context.setCompleted();
    }
}
