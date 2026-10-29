# Caseware Staff Java Software Developer — Take-Home Exercise

**Candidate:** Daniel Julián Gómez Silva  
**Tech Stack:** Java 25 LTS (Virtual Threads, JEP 513 Flexible Constructor Bodies, JEP 456 Unnamed Variables), Spring Boot `4.1.2`, Gradle `9.8.2` (Version Catalog), Micrometer, JUnit 5, AssertJ  
*(Note: The Java 21 LTS baseline implementation is preserved on branch [`feature/template-publish-fan-out-worker-java21`](https://github.com/dgomez84/caseWareTest/tree/feature/template-publish-fan-out-worker-java21)).*

---

## 1. Repository Structure & Deliverables

| Deliverable | File Path | Description |
| :--- | :--- | :--- |
| **1. Architecture Design Document (Part 1)** | [`docs/DESIGN_DOCUMENT.md`](docs/DESIGN_DOCUMENT.md) | 2–4 page Staff Architecture Design covering Data Ownership, Git-like Version DAG, Scale/Cost Arithmetic, Regulatory-Defensible AI Summaries (March $\rightarrow$ November), and Challenged Requirements. |
| **2. Targeted Implementation (Part 2)** | [`src/main/java/com/caseware/fanout/`](src/main/java/com/caseware/fanout/) | Spring Boot `4.1.2` `TemplatePublishFanOutWorker` using Virtual Threads, Downstream Capacity Governor, Multi-Tenant Fairness, Idempotency, and Pre-Flight Withdrawal Guard. |
| **3. Failure & Concurrency Test Suite** | [`src/test/java/com/caseware/fanout/worker/`](src/test/java/com/caseware/fanout/worker/) | Deterministic JUnit 5 concurrency/failure tests (`TemplatePublishFanOutWorkerTest`) + `@SpringBootTest` context verification (`TemplatePublishFanOutWorkerSpringTest`). |
| **4. AI Usage & Engineering Judgment Notes** | [`docs/AI_USAGE_NOTES.md`](docs/AI_USAGE_NOTES.md) | Detailed breakdown of where AI helped, where AI output was corrected/rejected, team guardrails, and where AI must not be trusted in audit workflows. |
| **5. Agentic Blueprint & Control Panel** | [`GEMINI.md`](GEMINI.md) & [`.agents/`](.agents/) | Project rules, tasks checklist, closed/open ADRs, and code annotations. |

---

## 2. Explicit Assumptions Made for This Narrow Component (Part 2)

Because the prompt specifies *"This is not a full application build. Implement one narrow component in Java: the template-publish fan-out worker"*, we made the following explicit boundary assumptions so the component is self-contained, production-structured, and deterministically testable without external cloud dependencies:

1. **A1 — Inbound Event Delivery Boundary:** We assume regional AWS SQS polling, message visibility extension, and JSON deserialization are handled by an upstream messaging adapter; our component's boundary starts at `TemplatePublishFanOutWorker.onTemplatePublished(TemplatePublishedEvent)`.
2. **A2 — Pre-Indexed Candidate Discovery (`EngagementLookupPort`):** We assume active engagements tracking a template and market branch (`~20,000` engagements per product on average) are retrieved from the regional `EngagementMetadataIndex` (DynamoDB) rather than scanning or rehydrating all `800,000` engagement files on every publish.
3. **A3 — Downstream Latency & Failure Contract (`DownstreamEngagementProcessorPort`):** We assume the downstream `Engagement Management System` is an external network call owned by another team that blocks for **~1 minute per engagement**, has a strictly bounded concurrency quota, and signals failures via:
   - `DownstreamThrottledException` (HTTP `429 Too Many Requests` with a `Retry-After` duration),
   - `RetryableDownstreamException` (transient network timeouts / HTTP `502`/`503`/`504`), or
   - `NonRetryableDownstreamException` (permanent client/data errors such as HTTP `400`/`404`/`422`).
4. **A4 — Per-Pod Concurrency Quota Partitioning (`DownstreamCapacityGovernor`):** We assume the global downstream concurrency budget (`MAX_CONCURRENCY`, e.g., `60` calls globally) is partitioned across the regional worker pods in EKS (e.g., `20` concurrent calls per pod across `3` pods), allowing an in-memory fair `Semaphore` + lock-free global `429` cooldown (`cooldownUntilNanoTime`) to govern capacity with zero network overhead per permit.
5. **A5 — Pluggable Idempotency Store (`FanOutIdempotencyStorePort`):** To keep tests fast and infrastructure-free, the default Spring bean (`InMemoryFanOutIdempotencyStore`) uses an atomic `ConcurrentHashMap.compute(...)`. In AWS production, this port is backed by regional DynamoDB conditional writes (`ConditionExpression: attribute_not_exists(pk) OR status <> :completed`).
6. **A6 — Low-Latency Template Lifecycle State (`TemplateVersionLifecyclePort`):** We assume checking whether a version is still `PUBLISHED` vs. `WITHDRAWN` or `SUPERSEDED` reads from a local in-memory cache (`Caffeine`) synchronized via SNS/SQS lifecycle events, adding zero latency before each 1-minute downstream call.

---

## 3. Key Implementation Tradeoffs & Alternatives Considered

| Design Decision | Alternative Considered | Why We Chose Our Approach (Tradeoff Analysis) |
| :--- | :--- | :--- |
| **1. Java 21/25 Virtual Threads (`newVirtualThreadPerTaskExecutor`) + `Semaphore`** | Fixed OS Thread Pool (`newFixedThreadPool`) or Reactive WebFlux (`Mono`/`Flux`) | OS threads consume ~1 MB stack each and cannot scale to thousands of 1-minute blocking calls. Reactive code obscures stack traces and complicates retry/semaphore control flow. Virtual Threads provide non-blocking OS scalability with clean, debuggable imperative code. |
| **2. Releasing Semaphore Permit *Before* Retry Backoff Sleep** | Acquiring Semaphore Permit *outside* the retry loop (`acquire -> retryLoop(call+sleep) -> release`) | Holding a scarce concurrency permit while sleeping on `429`/`503` exponential backoff starves healthy engagements and collapses throughput. We release the permit in `finally` before sleeping (`Test #3`). |
| **3. Multi-Tenant Round-Robin Scheduling (`FairTenantScheduler`)** | Processing engagements in raw database/FIFO order | Firm sizes are heavily skewed (`~40` median vs. `~40,000` max). FIFO order allows a single `40,000`-engagement firm to monopolize downstream capacity for hours (*Head-of-Line Blocking*). Round-Robin interleaving ensures median firms finish within minutes (`Test #5`). |
| **4. Upfront Idempotency Claim + Double Withdrawal Check** | Checking idempotency only after completing work, or not checking version withdrawal mid-batch | Since each downstream call costs ~1 minute of scarce capacity, we claim `(templateId, targetVersion, engagementId)` upfront (`Test #2`) and check `TemplateVersionLifecyclePort` both before and after waiting in the semaphore queue so withdrawn versions abort immediately (`Test #6`). |

---

## 4. What Was Deliberately Left Out of Scope

1. **Template Content Merge / Application Engine:** Explicitly excluded by the problem statement (*"Actually applying the updated template content is outside the scope of this assignment"*).
2. **Live AWS SDK Clients & Testcontainers:** Omitted so evaluators can clone the repository and run all concurrency, resilience, and Spring Boot tests in `< 3 seconds` without AWS credentials or Docker daemons.
3. **Distributed Lease Expiry (`TTL`) on Crashed `IN_PROGRESS` Claims:** Left out of the in-memory store for simplicity; in production DynamoDB, `IN_PROGRESS` claims carry a `lease_expires_at = now + 3m` so tasks from crashed pods are automatically reclaimed.

---

## 5. How to Verify, Test & Run Interactive Simulation (Zero Setup Required)

This repository includes the **Gradle Wrapper (`9.8.2`)** and the **Foojay Toolchain Resolver (`org.gradle.toolchains.foojay-resolver-convention`)**. Evaluators can clone any branch (`main`, `feature/template-publish-fan-out-worker-java21`, or `feature/template-publish-fan-out-worker-java25`) and run the verification suite directly—Gradle will automatically provision the matching JDK toolchain (Java 21 or Java 25) if not already installed locally.

### A. Run the Automated Concurrency, Failure & Spring Boot Test Suite
```bash
# Linux / macOS
./gradlew test

# Windows (PowerShell / CMD)
.\gradlew.bat test
```

### B. Run the Interactive Fan-Out Simulation (Default on `bootRun` / IntelliJ `Run`)
By default, `src/main/resources/application.yml` sets `caseware.fanout.simulation.enabled: true` so evaluators can immediately observe the `TemplatePublishFanOutWorker` processing a simulated batch in the console (demonstrating Round-Robin tenant fairness, HTTP `429` throttle recovery, DLQ isolation, duplicate-event idempotency, and Micrometer metrics) with zero extra flags:
- **In IntelliJ IDEA Ultimate:** Open [`CasewareFanOutWorkerApplication.java`](src/main/java/com/caseware/fanout/CasewareFanOutWorkerApplication.java) and click the green **Run (`▶`)** button next to `main()`.
- **Via Gradle CLI:**
```bash
# Linux / macOS
./gradlew bootRun

# Windows (PowerShell / CMD)
.\gradlew.bat bootRun
```

### C. Start the Service Without the Simulation (Standard Worker Mode)
To start the Spring Boot context with the simulation disabled (e.g., when wiring a real SQS inbound adapter or running in standard service mode), either set `caseware.fanout.simulation.enabled: false` in [`application.yml`](src/main/resources/application.yml) or pass the property override via CLI / IntelliJ Run Configuration *Program Arguments*:
```bash
# Linux / macOS
./gradlew bootRun --args='--caseware.fanout.simulation.enabled=false'

# Windows (PowerShell / CMD)
.\gradlew.bat bootRun --args="--caseware.fanout.simulation.enabled=false"
```
*(Note: `@SpringBootTest` automatically sets `caseware.fanout.simulation.enabled=false` so automated tests remain isolated).*

