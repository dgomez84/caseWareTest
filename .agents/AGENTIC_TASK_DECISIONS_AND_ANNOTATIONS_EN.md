# Agentic Control Panel: Tasks Checklist, ADR Decisions, Code Annotations & Open Topics (EN)

## 1. Coding & Implementation Tasks Checklist (`Tasks Checklist`)

### Phase 1: Analysis, Architecture & Documentation (Part 1)
- [x] **T-01:** Extract hard constraints and requirements from the 3 PDFs (Recruiter Email, Staff Java Developer JD, Take-Home Test).
- [x] **T-02:** Initialize `BITACORA_IA.md` for full session traceability.
- [x] **T-03:** Author Master Bilingual Technical Requirements Specification (`DOCUMENTO_TECNICO_REQUERIMIENTOS_Y_ARQUITECTURA.md` / `TECHNICAL_REQUIREMENTS_AND_ARCHITECTURE_EN.md`).
- [x] **T-04:** Author Deliverable 1 — *Architecture Design Document* (2–4 pages) in mirrored versions (`DESIGN_DOCUMENT_ES.md` / `docs/DESIGN_DOCUMENT.md`).
- [x] **T-05:** Author Deliverable 3 — *AI Usage & Engineering Judgment Notes* in mirrored versions (`AI_USAGE_NOTES_ES.md` / `docs/AI_USAGE_NOTES.md`).

### Phase 2: Targeted Java 21 Implementation (Part 2 — Branch `feature/template-publish-fan-out-worker-java21`)
- [x] **T-06:** Create Git branch `feature/template-publish-fan-out-worker-java21`.
- [x] **T-07:** Configure **Gradle `9.8.2`** (`gradle/wrapper/gradle-wrapper.properties`) and **Version Catalog** (`gradle/libs.versions.toml`).
- [x] **T-08:** Configure `build.gradle` with **Spring Boot `4.1.2`**, Java 21 Toolchain, and explicit Groovy DSL property assignment (`prop = value`).
- [x] **T-09:** Implement immutable domain models (`record`): `TemplatePublishedEvent`, `EngagementRef`, `IdempotencyKey`, `FanOutBatchReport`, `ProcessingStatus`, `TemplateVersionState`.
- [x] **T-10:** Implement downstream exception hierarchy: `DownstreamProcessingException`, `RetryableDownstreamException`, `DownstreamThrottledException`, `NonRetryableDownstreamException`.
- [x] **T-11:** Define Hexagonal Ports (`port/`): `EngagementLookupPort`, `DownstreamEngagementProcessorPort`, `FanOutIdempotencyStorePort`, `InMemoryFanOutIdempotencyStore`, `TemplateVersionLifecyclePort`, `DeadLetterPort`.
- [x] **T-12:** Implement resilience and tenant-fairness components (`resilience/`): `DownstreamCapacityGovernor`, `FairTenantScheduler`, `RetryPolicyConfig`.
- [x] **T-13:** Implement core service `TemplatePublishFanOutWorker` and Spring Boot 4 configuration (`FanOutWorkerConfig`, `FanOutWorkerProperties`, `application.yml`).
- [x] **T-14:** Implement unit/concurrency test suite (`TemplatePublishFanOutWorkerTest`) and Spring Boot integration test (`TemplatePublishFanOutWorkerSpringTest`).

### Phase 3: Remaining Tasks & LTS Evolution (`Next Steps`)
- [x] **T-15:** Verify clean Gradle `9.8.2` + Spring Boot `4.1.2` test execution in IntelliJ IDEA Ultimate on branch `feature/template-publish-fan-out-worker-java21`.
- [x] **T-16:** Integrate **Micrometer (`MeterRegistry`)** in `TemplatePublishFanOutWorker`.
- [x] **T-17:** Commit and push branch `feature/template-publish-fan-out-worker-java21` to GitHub.
- [x] **T-18:** Create branch **`feature/template-publish-fan-out-worker-java25`**, migrate project and Toolchain to **Java 25 LTS**, verify tests, and merge into `main` (subject to prior commit message and push approval).
- [ ] **T-19:** Final formatting review and submission preparation for Melissa Múnera.

---

## 2. Architectural & Implementation Decisions (Covered vs. Open)

### A. Covered Decisions (Closed ADRs)
| ID | Decision | Status | Rationale |
| :--- | :--- | :--- | :--- |
| **ADR-01** | Separate **Global Control Plane** (Templates/Diffs/Summaries) from **Regional Data Planes** (`EU`, `Canada`, `US`). | **Covered** | Enforces strict legal **Data Residency** without duplicating template storage or LLM calls. |
| **ADR-02** | Model non-linear template versions as a **Git-like DAG** with per-branch `head` pointers. | **Covered** | Supports market branching, `WITHDRAWN` versions, and merge-base / ancestor resolution. |
| **ADR-03** | **Regional DynamoDB Uncompressed JSON Index (`EngagementMetadataIndex`)** populated via live hooks & throttled backfill. | **Covered** | Eliminates the 1-minute engagement rehydration latency from dashboard queries (`< 25 ms`) while respecting the live system constraint. |
| **ADR-04** | `"Decline"` semantics: pins the transition tuple `(engagementId, base_v: v4, declined_target_v: v5)`. | **Covered** | Silences `v5`, while re-notifying on `v7` with the cumulative diff `v4 -> v7`. |
| **ADR-05** | Generate AI summaries per **version pair `(template_id, from_v -> to_v)`** ($O(\text{Version Pairs})$) + Deterministic Gate + **S3 Object Lock (WORM)**. | **Covered** | Cuts LLM cost from `$34,600/mo` to `~$39/mo` and guarantees March $\rightarrow$ November regulatory defensibility via `SHA-256`. |
| **ADR-06** | **Challenged Requirement:** Challenge *"within seconds"* publish visibility in favor of `< 2 min` post-verification + canary window. | **Covered** | Protects 4,000 firms against defective versions that are withdrawn shortly after publish. |
| **ADR-07** | **Part 2 Stack:** Gradle `9.8.2` + Version Catalog (`libs.versions.toml`) + Spring Boot `4.1.2` + Virtual Threads + Permit release before retry sleep + Round-Robin tenant fairness. | **Covered** | Defined and refined directly by Daniel. |
| **ADR-08** | **GitHub Branching & Merge Strategy:** Maintain `feature/template-publish-fan-out-worker-java21` (Java 21 LTS), `feature/template-publish-fan-out-worker-java25` (Java 25 LTS), and merge `java25` into `main` while keeping both feature branches published. | **Covered** | Satisfies the JD preference (*"Java 21 or Java 25 preferred"*) while keeping `main` on the latest LTS. |
| **ADR-09** | **Micrometer (`MeterRegistry`) Observability Instrumentation:** Inject `MeterRegistry` into `TemplatePublishFanOutWorker` recording counters (`completed`, `skipped.idempotent`, `skipped.obsolete`, `failed.dlq`, `downstream.throttled`) and gauge (`downstream.inflight`). | **Covered** | Directly connects Section 3 observability metrics in the Design Document to the code (including on the Java 21 branch). |
| **ADR-10** | **Interactive Simulation (`FanOutSimulationRunner`) & JDK Auto-Provisioning (`foojay-resolver`):** Enable interactive demo by default on `bootRun` / IntelliJ Run while disabling it in `@SpringBootTest`, plus automatic JDK provisioning via Foojay. | **Covered** | Enables Caseware evaluators to clone any branch and run tests or the live simulation in 1 click with zero manual setup. |

### B. Single Remaining Decision for Final Submission (`T-19`)
- [ ] **Email Attachment Format for Melissa Múnera:** In addition to the GitHub repository link (`https://github.com/dgomez84/caseWareTest.git`) containing `docs/DESIGN_DOCUMENT.md` and `docs/AI_USAGE_NOTES.md`, decide whether to also attach PDF exports directly to the reply email.

---

## 3. Code Annotations & Walkthrough (`Code Annotations`)

1. **`TemplatePublishFanOutWorker.java` (Lines 101–116) — Round-Robin Interleaving & Upfront Atomic Idempotency Claim:**
   - Orders candidate engagements via `FairTenantScheduler.interleaveByFirmRoundRobin(discovered)` and invokes `idempotencyStorePort.tryClaim(key)` **before** spawning virtual threads or acquiring concurrency permits.
2. **`TemplatePublishFanOutWorker.java` (Lines 131–145) — `Executors.newVirtualThreadPerTaskExecutor()` with `try-with-resources`:**
   - Uses structured lifetime management via `ExecutorService.close()` in Java 21+.
3. **`TemplatePublishFanOutWorker.java` (Lines 176–186) — `fairClaimedQueue.poll()` AFTER `capacityGovernor.acquirePermit()`:**
   - **Critical Concurrency Detail:** Polling the next engagement from `fairClaimedQueue` *immediately after* acquiring the first concurrency permit guarantees that tasks enter the downstream service in exact Round-Robin tenant order regardless of OS/JVM thread scheduling order.
4. **`TemplatePublishFanOutWorker.java` (Lines 221–233) — Permit Release in `finally` BEFORE `sleepQuietly(backoffDelay)`:**
   - Guarantees zero concurrency permits are held while a virtual thread sleeps during retry backoff.
5. **`DownstreamCapacityGovernor.java` (Lines 36–49 & 64–76) — Lock-Free Global Cooldown (`cooldownUntilNanoTime`):**
   - Uses monotonic `System.nanoTime()` and `AtomicLong.accumulateAndGet(..., Math::max)` to pause all virtual threads when the downstream service throws `DownstreamThrottledException` (HTTP 429).

---

## 4. Topics Deliberately Left Out of Part 2 Code (For Live Design Review Defense)

1. **Cross-Pod Distributed Concurrency Coordination in EKS:**
   - `DownstreamCapacityGovernor` uses an in-memory `Semaphore` per instance. In a multi-pod EKS deployment, we either partition the global concurrency limit (`maxConcurrency / podCount`) or back the governor with a DynamoDB/Redis distributed lease.
2. **Cursor-Based Pagination in `EngagementLookupPort`:**
   - `findAffectedEngagements` returns `List<EngagementRef>`. At `20,000` engagements (~100 bytes each $\approx 2\text{ MB}$ RAM), loading the refs in memory enables full Round-Robin interleaving across firms; at `10M+` engagements, we would use per-firm DynamoDB query cursors.
3. **Distributed Lease TTL in `FanOutIdempotencyStorePort`:**
   - `InMemoryFanOutIdempotencyStore` uses `ConcurrentHashMap`. In production, DynamoDB conditional writes include a `lease_expires_at` (`now + 3 minutes`) on `IN_PROGRESS` claims so a crashed pod's in-flight task can be safely recovered after lease expiry.
