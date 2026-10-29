# Antigravity Project Configuration — Caseware Template Fan-Out Worker

## Project Overview
This repository implements the **Part 2 Targeted Implementation (`TemplatePublishFanOutWorker`)** and houses the **Part 1 Architecture Design Document (`docs/DESIGN_DOCUMENT.md`)** for the Caseware Staff Java Software Developer Take-Home Exercise.

## Tech Stack & Build Conventions
- **Framework:** Spring Boot 4 (`4.1.2`) with Virtual Threads enabled (`spring.threads.virtual.enabled=true`).
- **Java Version:**
  - Branch `feature/template-publish-fan-out-worker-java21`: Java 21 LTS Toolchain.
  - Branch `feature/template-publish-fan-out-worker-java25`: Java 25 LTS Toolchain.
- **Build System:** Gradle `9.8.2` (`gradle/wrapper/gradle-wrapper.properties`) using **Gradle Version Catalog (`gradle/libs.versions.toml`)** so `build.gradle` contains zero hardcoded coordinates or versions. Always use explicit assignment syntax (`prop = value`) in Gradle Groovy DSL to avoid deprecation warnings in Gradle 8+/9+.
- **Testing:** JUnit 5 (`junit-jupiter`), AssertJ (`assertj-core`), and Spring Boot Test (`spring-boot-starter-test`).

## Core Architectural Invariants (Must Always Hold)
1. **Strict Downstream Capacity Protection:** Never exceed `DownstreamCapacityGovernor.maxConcurrency()` simultaneous calls to `DownstreamEngagementProcessorPort` (~1 minute per call).
2. **Semaphore-Safe Retry Loop:** Concurrency permits MUST be acquired *inside* each attempt and released in the `finally` block *before* sleeping on exponential backoff (`RetryPolicyConfig`). Never sleep while holding a downstream capacity permit.
3. **Multi-Tenant Round-Robin Fairness:** Candidate engagements MUST be interleaved across `firmId` via `FairTenantScheduler` so a `40,000`-engagement firm never starves `40`-engagement firms (Head-of-Line Blocking prevention).
4. **Atomic Idempotency & Pre-Flight Withdrawal Guard:** Claim `(templateId, targetVersion, engagementId)` via `FanOutIdempotencyStorePort.tryClaim` before dispatching, and verify `TemplateVersionLifecyclePort.getVersionState == PUBLISHED` both before and after waiting in the semaphore queue.
5. **Bilingual Documentation Rule:** Maintain synchronized English (`_EN.md` / default `.md`) and Spanish (`_ES.md`) versions of all architectural and README documentation.
