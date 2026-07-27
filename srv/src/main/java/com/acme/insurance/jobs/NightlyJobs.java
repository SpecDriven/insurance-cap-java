package com.acme.insurance.jobs;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.acme.insurance.billing.BillingManager;
import com.acme.insurance.domain.SystemContextRunner;
import com.acme.insurance.policy.CancellationManager;
import com.acme.insurance.policy.RenewalManager;

/**
 * Nightly jobs (ARCHITECTURE.md "Scheduling"): cron evaluated in the operating
 * timezone; the job logic lives in the plain managers, which tests invoke
 * directly with a fixed Clock.
 *
 * Runs the autopay charges/3-day retries (billing/collect-premium.feature.md),
 * executes elapsed cancellation notices (policy/auto-policy/cancel-policy.feature.md),
 * and generates renewal offers (policy/auto-policy/renew-policy.feature.md).
 * The [proposed] nightly issuance of cancellation notices stays absent.
 */
@Component
public class NightlyJobs {

    private final SystemContextRunner systemContext;
    private final BillingManager billing;
    private final CancellationManager cancellation;
    private final RenewalManager renewal;
    private final Clock clock;

    public NightlyJobs(SystemContextRunner systemContext, BillingManager billing,
            CancellationManager cancellation, RenewalManager renewal, Clock clock) {
        this.systemContext = systemContext;
        this.billing = billing;
        this.cancellation = cancellation;
        this.renewal = renewal;
        this.clock = clock;
    }

    @Scheduled(cron = "${insurance.jobs.nightly-cron}", zone = "${insurance.time-zone}")
    public void nightly() {
        LocalDate today = LocalDate.now(clock);
        systemContext.run(() -> {
            billing.runAutopay(today);
            cancellation.processDueCancellations(today);
            renewal.generateOffers(today);
        });
    }
}
