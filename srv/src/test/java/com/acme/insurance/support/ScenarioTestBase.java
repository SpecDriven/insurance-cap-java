package com.acme.insurance.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.acme.insurance.adapters.InMemoryPaymentGateway;
import com.acme.insurance.adapters.RecordingEmailAdapter;
import com.acme.insurance.adapters.RecordingTextMessageAdapter;
import com.acme.insurance.domain.InstallmentAllocator;
import com.acme.insurance.domain.Statuses;
import com.acme.insurance.domain.SystemContextRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.Installments;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;
import cds.gen.insurance.PolicyVehicles;

/**
 * Shared Spring context, recording stub adapters, fixed clock, and policy
 * fixtures for the published-scenario tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSupportConfig.class)
public abstract class ScenarioTestBase {

    protected static final LocalDate BASE_DATE = TestSupportConfig.BASE_DATE;

    @Autowired
    protected MockMvc mvc;
    protected final ObjectMapper json = new ObjectMapper();
    @Autowired
    protected PersistenceService persistence;
    @Autowired
    protected RecordingEmailAdapter emails;
    @Autowired
    protected RecordingTextMessageAdapter texts;
    @Autowired
    protected InMemoryPaymentGateway gateway;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected SystemContextRunner system;

    @BeforeEach
    void resetTestDoubles() {
        emails.reset();
        texts.reset();
        gateway.reset();
        clock.setBusinessDate(BASE_DATE);
    }

    /** Runs DB work inside a privileged request context. */
    protected <T> T db(Supplier<T> work) {
        return system.call(work);
    }

    protected void dbRun(Runnable work) {
        system.run(work);
    }

    protected JsonNode readTree(String content) {
        try {
            return json.readTree(content);
        } catch (Exception e) {
            throw new IllegalStateException("Response was not valid JSON: " + content, e);
        }
    }

    // ─── Policy fixtures (billing specs use premiums like $600 that are not on
    //     the current rate table, so fixtures insert policies directly) ───

    protected Policies insertPaidInFullAutoPolicy(BigDecimal premium, LocalDate effectiveDate,
            Consumer<Policies> customize) {
        Installments single = installment(1, effectiveDate, premium, BigDecimal.ZERO);
        single.setStatus(Statuses.INSTALLMENT_PAID);
        single.setPaidOn(effectiveDate);
        return insertPolicy(premium, effectiveDate, Statuses.PLAN_PAID_IN_FULL, List.of(single), customize);
    }

    /** Six monthly installments with the $5 service fee; the first is paid (down payment). */
    protected Policies insertMonthlyAutopayPolicy(BigDecimal premium, LocalDate effectiveDate,
            Consumer<Policies> customize) {
        List<BigDecimal> shares = InstallmentAllocator.allocate(premium, 6);
        List<Installments> schedule = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Installments inst = installment(i + 1, effectiveDate.plusMonths(i), shares.get(i),
                    new BigDecimal("5.00"));
            if (i == 0) {
                inst.setStatus(Statuses.INSTALLMENT_PAID);
                inst.setPaidOn(effectiveDate);
            }
            schedule.add(inst);
        }
        return insertPolicy(premium, effectiveDate, Statuses.PLAN_MONTHLY_AUTOPAY, schedule, customize);
    }

    protected Policies insertHomePolicy(Consumer<Policies> customize) {
        Policies policy = defaultPolicy(new BigDecimal("1420.00"), BASE_DATE.minusMonths(1),
                Statuses.PLAN_PAID_IN_FULL);
        policy.setLineOfBusiness(Statuses.LOB_HOME);
        policy.setFormType(Statuses.FORM_HO3);
        policy.setTermMonths(12);
        policy.setExpirationDate(policy.getEffectiveDate().plusMonths(12));
        policy.setDeductible(new BigDecimal("1000.00"));
        policy.setDwellingCoverage(new BigDecimal("300000.00"));
        policy.setPlan(null);
        if (customize != null) {
            customize.accept(policy);
        }
        Policies inserted = policy;
        return db(() -> {
            persistence.run(Insert.into(Policies_.class).entry(inserted));
            return reloadPolicy(inserted.getId());
        });
    }

    private Policies insertPolicy(BigDecimal premium, LocalDate effectiveDate, String billingPlan,
            List<Installments> installments, Consumer<Policies> customize) {
        Policies policy = defaultPolicy(premium, effectiveDate, billingPlan);
        policy.setInstallments(installments);
        if (customize != null) {
            customize.accept(policy);
        }
        return db(() -> {
            persistence.run(Insert.into(Policies_.class).entry(policy));
            return reloadPolicy(policy.getId());
        });
    }

    private Policies defaultPolicy(BigDecimal premium, LocalDate effectiveDate, String billingPlan) {
        Policies policy = Policies.create();
        policy.setId(UUID.randomUUID().toString());
        policy.setPolicyNumber("PA-T" + UUID.randomUUID().toString().substring(0, 8));
        policy.setLineOfBusiness(Statuses.LOB_AUTO);
        policy.setStatus(Statuses.POLICY_IN_FORCE);
        policy.setPlan("Standard");
        policy.setBodilyInjuryLimit("100/300");
        policy.setDeductible(new BigDecimal("1000.00"));
        policy.setPremium(premium);
        policy.setTermMonths(6);
        policy.setEffectiveDate(effectiveDate);
        policy.setExpirationDate(effectiveDate.plusMonths(6));
        policy.setNamedInsuredName("Jordan Smith");
        policy.setNamedInsuredEmail("jordan.smith@example.com");
        policy.setNamedInsuredPhone("+1-555-0100");
        policy.setGaragingZip("94105");
        policy.setBillingPlan(billingPlan);
        policy.setPaymentMethodToken("tok-" + UUID.randomUUID().toString().substring(0, 8));

        PolicyVehicles vehicle = PolicyVehicles.create();
        vehicle.setId(UUID.randomUUID().toString());
        vehicle.setMake("Toyota");
        vehicle.setModel("Camry");
        vehicle.setModelYear(2023);
        vehicle.setAddedOn(effectiveDate);
        policy.setVehicles(List.of(vehicle));
        return policy;
    }

    private Installments installment(int seq, LocalDate dueDate, BigDecimal premiumAmount, BigDecimal fee) {
        Installments inst = Installments.create();
        inst.setId(UUID.randomUUID().toString());
        inst.setSeq(seq);
        inst.setDueDate(dueDate);
        inst.setPremiumAmount(premiumAmount);
        inst.setServiceFee(fee);
        inst.setTotalAmount(premiumAmount.add(fee));
        inst.setStatus(Statuses.INSTALLMENT_SCHEDULED);
        return inst;
    }

    protected Policies reloadPolicy(String policyId) {
        return persistence.run(Select.from(Policies_.class)
                .where(p -> p.ID().eq(policyId))
                .columns(p -> p._all(), p -> p.installments().expand(), p -> p.vehicles().expand()))
                .single(Policies.class);
    }

    protected Policies reloadPolicyTx(String policyId) {
        return db(() -> reloadPolicy(policyId));
    }
}
