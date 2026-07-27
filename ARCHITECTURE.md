# Development Stack

- SAP CAP Java 5.x (latest minor) — the governing dependency; it dictates
  the Spring and JDK baselines below
- Spring Boot 4.x (Spring Framework 7), provided via CAP
- Java 25
- Maven 3.9.14+
- Liquibase for schema evolution
- H2 database for dev, test, and demo runs — no cloud DB target for now
  (PostgreSQL or HANA Cloud can be added later without model changes)
- JUnit 5

# UI

SAP UI5 with **Fiori Elements** over OData V4: List Report / Object Pages
generated from CDS annotations. Freestyle UI5 islands only where a flow
doesn't fit Fiori Elements (e.g. the quote questionnaire).

# Conventions

**Timezone policy:** every business date (due dates, grace periods,
effective/cancellation dates, "expiring in 45 days") is computed in the
company's operating timezone, configured by `insurance.time-zone`
(default `America/New_York`) and injected as `java.time.Clock`. Services never
call `now()` without the Clock. Audit/technical timestamps (`@cds.on.insert`
etc.) remain UTC instants; the operating timezone governs business dates only.

**Pro-rata convention:** terms are equal monthly units. A partial month counts
elapsed days ÷ the actual length of that policy month, anchor-to-anchor
(actual/actual — e.g. Mar 15→Apr 15 is a 31-day month). So a $600 six-month
policy cancelled exactly 2 months in refunds exactly $400 — matching the
published billing spec.

**Money:** amounts are CDS `Decimal` (`BigDecimal` in Java), rounded HALF_UP
to cents. Installment plans allocate remainders largest-first so the
installments sum exactly to premium plus fees (e.g. $517 over 6 payments).

**Scheduling:** nightly jobs (renewal offers, autopay retry in 3 days) are
Spring `@Scheduled` beans with cron evaluated in the operating timezone. Job
logic lives in plain services that tests invoke directly with a fixed Clock.

**Outbound integrations:** email, text messaging, and the payment gateway
(autopay charges/declines, refunds to the original payment method, direct pay
to repair shops) are ports with in-memory stub adapters; tests assert on the
recorded outbound calls. No real providers are wired.

**Rate data:** rating tables (plan premiums, deductibles, installment service
fees) are CSV seed data under `db/data/`, not constants in code.

**Auth:** CAP mock users with roles `prospect`, `insured`, `agent`, and
`adjuster` for dev and tests.

**Spec traceability:** only `[published]` scenarios are implemented; scenarios
marked `[proposed]` are intentionally absent. Each service/test carries a
comment naming its spec file, and tests' `@DisplayName`s quote the scenario
titles. Where a published scenario depends on an artifact whose producing
scenario is still `[proposed]` — the cancellation notice presupposed by
cancel-for-nonpayment — the artifact is modeled minimally (a
`CancellationNotice` entity with an explicit issue action) while the proposed
automation (nightly issuance) stays absent.
