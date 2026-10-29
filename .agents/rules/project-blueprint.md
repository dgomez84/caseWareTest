---
trigger: always_on
description: Blueprint and specification rules for regenerating and evolving the Caseware Template-Publish Fan-Out Worker in Antigravity.
---

# Caseware Template-Publish Fan-Out Worker — Agentic Blueprint

## 1. Domain Problem & Hard Constraints
- **Scale:** `4,000` firms, `800,000` active Engagement Files across `40` Product Templates (`~20,000` engagements/product avg).
- **Skew:** Median firm has `~40` engagements; largest firm has `~40,000` engagements.
- **Downstream Constraint:** The downstream `Engagement Management System` takes **~1 minute per engagement** to load/process and is owned by another team with strictly limited capacity.
- **Non-Linear Template DAG:** Template versions branch by market, accumulate across multiple skipped updates, and may be `WITHDRAWN` after publication.

## 2. Package & Component Structure (`com.caseware.fanout`)
- `CasewareFanOutWorkerApplication`: `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/`:
  - `FanOutWorkerProperties`: `@ConfigurationProperties(prefix = "caseware.fanout")` binding `maxConcurrency` and `RetryPolicyConfig` from `application.yml`.
  - `FanOutWorkerConfig`: Spring `@Configuration` wiring `DownstreamCapacityGovernor` and `RetryPolicyConfig` beans.
- `model/`: Immutable Java records (`TemplatePublishedEvent`, `EngagementRef`, `IdempotencyKey`, `FanOutBatchReport`) and enums (`ProcessingStatus`, `TemplateVersionState`).
- `port/`: Hexagonal ports (`EngagementLookupPort`, `DownstreamEngagementProcessorPort`, `FanOutIdempotencyStorePort`, `TemplateVersionLifecyclePort`, `DeadLetterPort`).
- `exception/`: `DownstreamProcessingException`, `RetryableDownstreamException`, `DownstreamThrottledException`, `NonRetryableDownstreamException`.
- `resilience/`: `DownstreamCapacityGovernor`, `FairTenantScheduler`, `RetryPolicyConfig`.
- `simulation/`: `FanOutSimulationRunner` (Interactive CLI/IntelliJ simulation enabled by default on `bootRun` and disabled in `@SpringBootTest`).
- `worker/`: `TemplatePublishFanOutWorker` (`@Service`, instrumented with Micrometer `MeterRegistry` counters and gauge).

## 3. Mandatory Verification Suite (`TemplatePublishFanOutWorkerTest` & `TemplatePublishFanOutWorkerSpringTest`)
Every modification must keep all unit and Spring Boot integration tests passing:
1. Concurrency limit enforcement (`peakObservedInFlight <= maxConcurrency`).
2. Idempotency on duplicate `TemplatePublishedEvent` delivery.
3. Permit release before retry backoff sleep on transient / HTTP 429 failures.
4. Permanent failure isolation (`NonRetryableDownstreamException` -> 1 attempt -> `DeadLetterPort`) and retry exhaustion (`3` attempts -> `DeadLetterPort`).
5. Multi-tenant Round-Robin fairness across skewed firms (`40,000` vs `40`).
6. Immediate cancellation of queued tasks when `TemplateVersionState` becomes `WITHDRAWN`.
