# AI Usage Notes & Engineering Judgment
**Candidate:** Daniel Julián Gómez Silva — *Staff Java Software Developer Candidate*
**Tooling Used:** Google Antigravity IDE/CLI (Gemini 4) + IntelliJ IDEA Ultimate

---

## 1. Where AI Helped Me
1. **Spec-Driven Structuring & Bilingual Verification:** As a native Spanish speaker working in global English environments, I used the AI assistant to maintain synchronized Spanish and English mirrors of our Technical Requirements Specification and Architecture Design Document. This allowed rapid, zero-ambiguity validation of every architectural nuance before committing to code.
2. **Scale & Cost Arithmetic Cross-Checking:** AI accelerated the tabulation of capacity and cost models—specifically contrasting naive per-engagement LLM summarization ($3.46\text{M calls/month} \approx \$34.6\text{k/month}$) against DAG version-pair caching ($865\text{ calls/month} \approx \$39\text{/month}$), DynamoDB storage sizing, and off-peak backfill duration math.
3. **Java 21 Boilerplate & Failure-Injection Test Harnessing:** Once interfaces and concurrency invariants were locked in the specification, AI accelerated writing immutable Java `record` types, hexagonal port interfaces, and deterministic JUnit 5 concurrency tests simulating downstream `429` throttling, retries, and partial batch failures.

---

## 2. Where I Corrected or Ignored AI Output
1. **Git-Like Version DAG & Identity-Driven Regional Routing:**
   - Early AI suggestions framed non-linear template evolution as generic state transitions. Drawing on my microservices architecture experience, I directed the design to model template versions explicitly as a **Git-like Directed Acyclic Graph (DAG)** with branch `head` pointers and deterministic RFC 6902 JSON diffs, coupled with tenant-region resolution (`EU`, `Canada`) directly at the authentication/gateway layer.
2. **Uncompressed JSON Metadata in DynamoDB vs. Respecting the 1-Minute Legacy Constraint:**
   - I wanted uncompressed JSON documents in DynamoDB holding fixed template/version headers to eliminate decompression latency on reads. When contrasting this with the prompt's hard constraint (*"loading the engagement is a slow operation ~1 minute per file; this should be treated as a hard constraint"* on a live system with no maintenance window), we refined the design so DynamoDB acts as an event-driven **Shadow Metadata Index (`EngagementMetadataIndex`)** populated via live hooks and throttled backfill—rather than assuming we could rewrite the legacy engagement storage format.
3. **Rejecting "Semaphore Held Across Retry Sleep" (A Classic AI Concurrency Anti-Pattern):**
   - Standard LLM-generated concurrency code frequently acquires a `Semaphore` permit *outside* the retry loop (`acquire() -> retryLoop(call + backoffSleep) -> release()`). **I explicitly rejected this pattern:** when a downstream service is already struggling and returns `429 Too Many Requests`, sleeping during exponential backoff while holding a concurrency permit starves other workers and wastes precious downstream capacity. In our `TemplatePublishFanOutWorker`, permits are acquired **per attempt** and released **immediately before** any backoff sleep.
4. **Preventing Multi-Tenant Starvation (40,000-Engagement Firm vs. 40-Engagement Firm):**
   - A default FIFO fan-out loop would allow the largest firm (`~40,000` engagements) to monopolize the limited downstream concurrency pool for hours, starving hundreds of median firms (`~40` engagements). I required a **`FairTenantScheduler` (Round-Robin interleaving by `firmId`)** so small and medium firms complete their updates in minutes without head-of-line blocking.
5. **Build Tool, Version Catalog, Framework & LTS Evolution (Gradle 9.8.2 + `libs.versions.toml` + Spring Boot 4.1.2 + Java 21/25 Branches):**
   - The AI initially generated a bare Java + Maven (`pom.xml`) setup. I overrode this to use **Gradle `9.8.2`** with a **Gradle Version Catalog (`gradle/libs.versions.toml`)** and **Spring Boot `4.1.2`** (`@Service`, `@ConfigurationProperties`, `spring.threads.virtual.enabled=true`, and `@SpringBootTest`), fixed a Gradle 8/9 Groovy DSL property-assignment deprecation warning in `testLogging` caught during my IntelliJ test run, and structured the repository across `feature/template-publish-fan-out-worker-java21` and `feature/template-publish-fan-out-worker-java25` to align with the JD's preference for Java 21 / Java 25 LTS.

---

## 3. How I Would Guide Other Engineers Using AI on This System
1. **Spec-First, Code-Second (No "Vibe Coding" in Distributed Systems):** Engineers must author and review a concise technical spec defining idempotency keys, failure modes, and concurrency bounds *before* prompting an agent to generate worker code.
2. **Mandatory Invariant Testing for AI-Generated Concurrency Code:** AI-generated code using `VirtualThreads`, `Semaphore`, or `CompletableFuture` often compiles cleanly while harboring subtle race conditions or resource leaks. Every AI-assisted PR must include deterministic concurrency tests proving that `maxObservedInFlight <= maxConcurrency` under failure and retry storms.
3. **Strict Data-Residency Guardrails:** Enforce static analysis and architectural review rules ensuring AI-generated logging, telemetry, or prompt templates never serialize confidential `Engagement File` payloads outside their regional cell (`EU`, `Canada`).

---

## 4. Where AI Should NOT Be Trusted in This Domain
1. **Computing Raw Template Diffs via LLMs:** Never pass two raw template ZIP/JSON files to an LLM and ask it to "find the differences." LLMs suffer from attention degradation over large JSON trees and can silently miss a critical audit formula change. **Diff extraction must be 100% deterministic (RFC 6902 JSON Patch)**; the LLM is only used to translate an already extracted, categorized diff into human-readable prose.
2. **Unverified or Ephemeral On-the-Fly Summaries (March $\rightarrow$ November Regulatory Risk):** An LLM must never generate unverified summaries on the fly per user view. Every summary must pass a **Deterministic Verification Gate** proving 100% recall of critical diff paths (`source_diff_paths[]`) and must be stored immutably in WORM storage (S3 Object Lock) with its `SHA-256` hash recorded in the `RegulatoryDecisionLedger`.
3. **Financial Calculations & Workpaper Merging:** Applying template updates or recalculating volatile financial fields inside an engagement file must remain 100% deterministic code—never delegated to probabilistic inference.
