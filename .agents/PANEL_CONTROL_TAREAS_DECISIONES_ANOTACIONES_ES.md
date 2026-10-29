# Panel de Control Agentic: Tareas, Decisiones, Anotaciones de Código y Temas Abiertos (ES)

## 1. Lista de Chequeo de Tareas de Codificación e Implementación (`Tasks Checklist`)

### Fase 1: Análisis, Arquitectura y Documentación (Parte 1)
- [x] **T-01:** Lectura y extracción de restricciones duras de los 3 PDFs (Correo Alcor, JD Staff Java Developer, Take-Home Test).
- [x] **T-02:** Creación de la `BITACORA_IA.md` para trazabilidad de todas las sesiones.
- [x] **T-03:** Definición del Documento Técnico Maestro Bilingüe (`DOCUMENTO_TECNICO_REQUERIMIENTOS_Y_ARQUITECTURA.md` / `TECHNICAL_REQUIREMENTS_AND_ARCHITECTURE_EN.md`).
- [x] **T-04:** Redacción del Entregable 1 — *Architecture Design Document* (2–4 páginas) en versiones espejo (`DESIGN_DOCUMENT_ES.md` / `docs/DESIGN_DOCUMENT.md`).
- [x] **T-05:** Redacción del Entregable 3 — *AI Usage & Engineering Judgment Notes* en versiones espejo (`AI_USAGE_NOTES_ES.md` / `docs/AI_USAGE_NOTES.md`).

### Fase 2: Implementación del Componente Acotado en Java 21 (Parte 2 — Rama `feature/template-publish-fan-out-worker-java21`)
- [x] **T-06:** Creación de rama Git `feature/template-publish-fan-out-worker-java21`.
- [x] **T-07:** Configuración de **Gradle `9.8.2`** (`gradle/wrapper/gradle-wrapper.properties`) y **Version Catalog** (`gradle/libs.versions.toml`).
- [x] **T-08:** Configuración de `build.gradle` con **Spring Boot `4.1.2`**, Java 21 Toolchain y corrección de sintaxis Groovy DSL (`prop = value`).
- [x] **T-09:** Implementación de modelos de dominio inmutables (`record`): `TemplatePublishedEvent`, `EngagementRef`, `IdempotencyKey`, `FanOutBatchReport`, `ProcessingStatus`, `TemplateVersionState`.
- [x] **T-10:** Implementación de jerarquía de excepciones downstream: `DownstreamProcessingException`, `RetryableDownstreamException`, `DownstreamThrottledException`, `NonRetryableDownstreamException`.
- [x] **T-11:** Definición de Puertos Hexagonales (`port/`): `EngagementLookupPort`, `DownstreamEngagementProcessorPort`, `FanOutIdempotencyStorePort`, `InMemoryFanOutIdempotencyStore`, `TemplateVersionLifecyclePort`, `DeadLetterPort`.
- [x] **T-12:** Implementación de componentes de resiliencia y planificación justa (`resilience/`): `DownstreamCapacityGovernor`, `FairTenantScheduler`, `RetryPolicyConfig`.
- [x] **T-13:** Implementación del servicio principal `TemplatePublishFanOutWorker` y configuración Spring Boot 4 (`FanOutWorkerConfig`, `FanOutWorkerProperties`, `application.yml`).
- [x] **T-14:** Implementación de suite de pruebas unitarias/concurrencia (`TemplatePublishFanOutWorkerTest`) y prueba de integración Spring Boot (`TemplatePublishFanOutWorkerSpringTest`).

### Fase 3: Tareas de Cierre y Evolución LTS (`Next Steps`)
- [x] **T-15:** Confirmar ejecución limpia en IntelliJ IDEA Ultimate con Gradle `9.8.2` y Spring Boot `4.1.2` en la rama `feature/template-publish-fan-out-worker-java21`.
- [x] **T-16:** Integrar **Micrometer (`MeterRegistry`)** en `TemplatePublishFanOutWorker`.
- [x] **T-17:** Realizar commit y push de la rama `feature/template-publish-fan-out-worker-java21` a GitHub.
- [x] **T-18:** Crear la rama **`feature/template-publish-fan-out-worker-java25`**, migrar el proyecto y su Toolchain a **Java 25 LTS**, verificar pruebas y hacer merge hacia `main` (previa aprobación de mensaje de commit y push).
- [ ] **T-19:** Revisión final de formato y preparación de entrega para responder el correo a Melissa Múnera.

---

## 2. Lista de Decisiones Arquitectónicas y de Diseño (Cubiertas vs. Por Cubrir)

### A. Decisiones Ya Cubiertas y Cerradas (ADRs)
| ID | Decisión | Estado | Justificación |
| :--- | :--- | :--- | :--- |
| **ADR-01** | Separación **Global Control Plane** (Plantillas/Diffs/Resúmenes) vs. **Regional Data Plane** (`EU`, `Canada`, `US`). | **Cubierta** | Garantiza **Data Residency** legal sin duplicar el motor de plantillas ni los resúmenes IA. |
| **ADR-02** | Modelo de versionado no lineal de plantillas como **Grafo DAG tipo Git** con punteros `head` por rama de mercado. | **Cubierta** | Soporta ramificación por mercado, versiones retiradas (`WITHDRAWN`) y cálculo de ancestros comunes. |
| **ADR-03** | **Índice JSON sin comprimir en DynamoDB Regional (`EngagementMetadataIndex`)** sincronizado por eventos/backfill. | **Cubierta** | Evita la latencia de 1 minuto de rehidratación del archivo de encargo en las consultas del dashboard (`< 25 ms`) sin violar la restricción dura del sistema en vivo. |
| **ADR-04** | Semántica de `"Decline"`: ancla la arista `(engagementId, base_v: v4, declined_target_v: v5)`. | **Cubierta** | Silencia `v5`, pero al salir `v7` reactiva la alerta calculando el diff acumulado `v4 -> v7`. |
| **ADR-05** | Generación de resúmenes IA por **par de versiones `(template_id, from_v -> to_v)`** ($O(\text{Version Pairs})$) + Compuerta Determinista + **S3 Object Lock (WORM)**. | **Cubierta** | Reduce el costo de IA de `$34,600/mes` a `~$39/mes` y garantiza defendibilidad regulatoria de Marzo a Noviembre mediante hash `SHA-256`. |
| **ADR-06** | **Requisito desafiado:** Desafiar la visibilidad *"en segundos"* tras publicar a favor de `< 2 min` post-validación IA + ventana Canary. | **Cubierta** | Protege contra versiones defectuosas que se retiran poco después de publicarse. |
| **ADR-07** | **Stack Parte 2:** Gradle `9.8.2` + Version Catalog (`libs.versions.toml`) + Spring Boot `4.1.2` + Virtual Threads + Semáforo liberado antes del backoff + Round-Robin por firma. | **Cubierta** | Definido y corregido directamente por Daniel. |
| **ADR-08** | **Estrategia de Ramas y Entrega en GitHub:** Rama `feature/template-publish-fan-out-worker-java21` (Java 21 LTS), rama `feature/template-publish-fan-out-worker-java25` (Java 25 LTS) y merge final de `java25` hacia `main` conservando ambas ramas publicadas. | **Cubierta** | Cumple con la preferencia del JD (*"Java 21 or Java 25 preferred"*) y deja `main` en la última LTS estable. |
| **ADR-09** | **Instrumentación con Micrometer (`MeterRegistry`):** Inyección de `MeterRegistry` en `TemplatePublishFanOutWorker` registrando contadores (`completed`, `skipped.idempotent`, `skipped.obsolete`, `failed.dlq`, `downstream.throttled`) y gauge (`downstream.inflight`). | **Cubierta** | Conecta directamente las métricas de observabilidad de la Sección 3 del diseño con la implementación en código (incluyendo en la rama Java 21). |
| **ADR-10** | **Simulación Interactiva (`FanOutSimulationRunner`) y Auto-Provisioning de JDK (`foojay-resolver`):** Ejecución demostrativa activa por defecto en `bootRun` / IntelliJ Run y desactivada en `@SpringBootTest`, más descarga automática del JDK vía Foojay. | **Cubierta** | Permite a cualquier evaluador de Caseware clonar y probar tanto los tests como la simulación en 1 clic sin configuración manual. |

### B. Única Decisión Pendiente para el Cierre Final (`T-19`)
- [ ] **Formato de adjunto en el correo a Melissa Múnera:** Además del enlace al repositorio GitHub (`https://github.com/dgomez84/caseWareTest.git`) que ya incluye `docs/DESIGN_DOCUMENT.md` y `docs/AI_USAGE_NOTES.md`, decidir si adjuntamos también copias en PDF en el correo de respuesta.

---

## 3. Mapa de Anotaciones del Código (`Code Annotations & Walkthrough`)

1. **`TemplatePublishFanOutWorker.java` (Líneas 101–116) — Intercalado Round-Robin y Reclamo Atómico de Idempotencia Pre-Despacho:**
   - Primero ordena los encargos con `FairTenantScheduler.interleaveByFirmRoundRobin(discovered)` y luego ejecuta `idempotencyStorePort.tryClaim(key)` **antes** de crear hilos virtuales o competir por el semáforo. Esto evita desperdiciar hilos o permisos de concurrencia en encargos que ya fueron procesados en una entrega previa de SQS.
2. **`TemplatePublishFanOutWorker.java` (Líneas 131–145) — `Executors.newVirtualThreadPerTaskExecutor()` con `try-with-resources`:**
   - Aprovecha el *structured lifetime* de `ExecutorService.close()` en Java 21+, asegurando que el lote espere limpiamente la finalización de todas las tareas en vuelo.
3. **`TemplatePublishFanOutWorker.java` (Líneas 176–186) — `fairClaimedQueue.poll()` DESPUÉS de `capacityGovernor.acquirePermit()`:**
   - **Detalle sutil de concurrencia:** Si asignáramos un `EngagementRef` fijo a cada hilo virtual antes de `acquirePermit()`, el planificador del SO podría despertar al hilo #50 antes que al hilo #2, rompiendo el orden Round-Robin entre firmas. Al hacer `fairClaimedQueue.poll()` **inmediatamente después** de adquirir el primer permiso del semáforo, garantizamos matemáticamente que los permisos se asignen en el orden exacto de la cola justa.
4. **`TemplatePublishFanOutWorker.java` (Líneas 221–233) — Liberación del Semáforo en `finally` ANTES del `sleepQuietly(backoffDelay)`:**
   - Garantiza que ningún hilo en espera de backoff retenga un permiso de capacidad del servicio downstream.
5. **`DownstreamCapacityGovernor.java` (Líneas 36–49 y 64–76) — Ventana de Enfriamiento Global (`cooldownUntilNanoTime`):**
   - Usa `System.nanoTime()` monótono (inmune a saltos de reloj NTP) y `AtomicLong.accumulateAndGet(..., Math::max)` para coordinar sin bloqueos (*lock-free*) la pausa global cuando el downstream lanza `DownstreamThrottledException` (HTTP 429).

---

## 4. Temas Deliberadamente No Cubiertos en Código (Para Defender en la Entrevista)

1. **Coordinación Distribuida de Concurrencia entre Múltiples Pods en EKS:**
   - En el ejercicio, `DownstreamCapacityGovernor` usa un `Semaphore` en memoria por instancia. En producción con `N` pods, o bien asignamos una cuota estática `maxConcurrency / N` por pod, o usamos un *Distributed Lease / Token Bucket* en Redis/DynamoDB con TTL corto.
2. **Paginación por Cursores (Streaming) en `EngagementLookupPort`:**
   - Actualmente `findAffectedEngagements` devuelve `List<EngagementRef>`. Para `20,000` objetos `EngagementRef` (~100 bytes cada uno $\approx 2\text{ MB}$ en RAM), cabe fácilmente en memoria y permite hacer el *Round-Robin* completo entre firmas; si fueran `10 millones` de registros, usaríamos cursores paginados por firma en DynamoDB.
3. **Persistencia Distribuida del `FanOutIdempotencyStorePort` con Lease TTL:**
   - La implementación de referencia `InMemoryFanOutIdempotencyStore` usa `ConcurrentHashMap`. En producción, el adaptador real usa escritura condicional en **DynamoDB** con un `lease_expires_at` (por ejemplo, `now + 3 minutos`) para el estado `IN_PROGRESS`, de modo que si un pod muere por `OOMKilled` a mitad de la llamada de 1 minuto, otro pod pueda reclamar la tarea tras expirar el lease.
