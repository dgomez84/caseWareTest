# Caseware Staff Java Software Developer — Ejercicio Técnico (Versión en Español)

**Candidato:** Daniel Julián Gómez Silva  
**Stack Tecnológico:** Java 21 / Java 25 LTS (Virtual Threads), Spring Boot `4.1.2`, Gradle `9.8.2` (Version Catalog), JUnit 5, AssertJ

---

## 1. Estructura del Repositorio y Entregables

| Entregable | Ruta | Descripción |
| :--- | :--- | :--- |
| **1. Documento de Diseño Arquitectónico (Parte 1)** | [`docs/DESIGN_DOCUMENT.md`](docs/DESIGN_DOCUMENT.md) | Diseño de Arquitectura a nivel Staff (2–4 páginas) cubriendo Propiedad de Datos, Grafo DAG tipo Git, Aritmética de Escala/Costo, Resúmenes IA Defendibles ante Regulador (Marzo $\rightarrow$ Noviembre) y Requisito Desafiado. |
| **2. Implementación Acotada (Parte 2)** | [`src/main/java/com/caseware/fanout/`](src/main/java/com/caseware/fanout/) | `TemplatePublishFanOutWorker` en Spring Boot `4.1.2` con Virtual Threads, Gobernador de Capacidad Downstream, Justicia Multi-Tenant (Round-Robin), Idempotencia y Guardia de Retiro de Versión. |
| **3. Suite de Pruebas de Fallo y Concurrencia** | [`src/test/java/com/caseware/fanout/worker/`](src/test/java/com/caseware/fanout/worker/) | Pruebas deterministas en JUnit 5 (`TemplatePublishFanOutWorkerTest`) + verificación de contexto `@SpringBootTest` (`TemplatePublishFanOutWorkerSpringTest`). |
| **4. Notas de Uso de IA y Criterio de Ingeniería** | [`docs/AI_USAGE_NOTES.md`](docs/AI_USAGE_NOTES.md) | Detalle de dónde ayudó la IA, dónde corregí o descarté sus salidas, guías para el equipo y dónde NO confiar en la IA en este dominio. |
| **5. Blueprint Agentic y Panel de Control** | [`GEMINI.md`](GEMINI.md) y [`.agents/`](.agents/) | Reglas del proyecto, lista de chequeo de tareas, ADRs cerrados/abiertos y anotaciones del código. |

---

## 2. Asunciones Explícitas Tomadas para Este Componente Acotado (Parte 2)

Dado que el enunciado especifica *"This is not a full application build. Implement one narrow component in Java: the template-publish fan-out worker"*, tomamos las siguientes **6 asunciones explícitas de frontera** para que el componente funcione de manera autónoma, con estructura de producción y sea 100% verificable sin dependencias externas de nube:

1. **A1 — Frontera de Consumo del Evento:** Asumimos que el *polling* de AWS SQS regional, la extensión del *Visibility Timeout* y la deserialización JSON son manejados por el adaptador de mensajería de entrada; la responsabilidad de nuestro componente inicia en `TemplatePublishFanOutWorker.onTemplatePublished(TemplatePublishedEvent)`.
2. **A2 — Descubrimiento Pre-Indexado de Encargos (`EngagementLookupPort`):** Asumimos que los Archivos de Encargo activos que usan una plantilla y rama de mercado (`~20,000` en promedio por producto) se consultan desde el `EngagementMetadataIndex` regional (DynamoDB) en lugar de escanear o rehidratar los `800,000` archivos en cada publicación.
3. **A3 — Contrato de Latencia y Fallos del Sistema Downstream (`DownstreamEngagementProcessorPort`):** Asumimos que el `Engagement Management System` es un servicio externo propiedad de otro equipo donde cada llamada bloquea por **~1 minuto por encargo**, tiene una cuota de concurrencia estricta y reporta fallos mediante:
   - `DownstreamThrottledException` (HTTP `429 Too Many Requests` con duración `Retry-After`),
   - `RetryableDownstreamException` (timeouts de red transitorios / HTTP `502`/`503`/`504`), o
   - `NonRetryableDownstreamException` (errores permanentes de datos/cliente como HTTP `400`/`404`/`422`).
4. **A4 — Particionamiento de Cuota de Concurrencia por Pod (`DownstreamCapacityGovernor`):** Asumimos que el presupuesto global de concurrencia downstream (`MAX_CONCURRENCY`, p. ej. `60` llamadas globales) se particiona entre los pods regionales del worker en EKS (p. ej. `20` llamadas concurrentes por pod en `3` pods), permitiendo que un `Semaphore` justo en memoria + enfriamiento global sin bloqueos ante HTTP `429` (`cooldownUntilNanoTime`) gobiernen la capacidad sin latencia de red adicional por permiso.
5. **A5 — Almacén de Idempotencia Conectable (`FanOutIdempotencyStorePort`):** Para que las pruebas corran en milisegundos sin infraestructura externa, el bean por defecto (`InMemoryFanOutIdempotencyStore`) usa un `ConcurrentHashMap.compute(...)` atómico. En producción sobre AWS, este puerto se conecta a escrituras condicionales de DynamoDB (`ConditionExpression: attribute_not_exists(pk) OR status <> :completed`).
6. **A6 — Consulta de Baja Latencia del Estado de la Versión (`TemplateVersionLifecyclePort`):** Asumimos que verificar si una versión sigue `PUBLISHED` frente a `WITHDRAWN` o `SUPERSEDED` lee de una caché local en memoria (`Caffeine`) actualizada por eventos SNS/SQS, agregando cero latencia antes de cada llamada de 1 minuto.

---

## 3. Tradeoffs Clave de Implementación y Alternativas Consideradas

| Decisión de Diseño | Alternativa Considerada | Por Qué Elegimos Nuestro Enfoque (Análisis de Tradeoff) |
| :--- | :--- | :--- |
| **1. Java 21/25 Virtual Threads (`newVirtualThreadPerTaskExecutor`) + `Semaphore`** | Pool fijo de hilos de SO (`newFixedThreadPool`) o Programación Reactiva (`WebFlux` / `Reactor`) | Los hilos de SO consumen ~1 MB de stack y no escalan bien con miles de llamadas bloqueadas por 1 minuto. El código reactivo dificulta la depuración y lectura de trazas. Los Virtual Threads dan escalabilidad no bloqueante con código imperativo limpio. |
| **2. Liberar el Permiso del Semáforo *Antes* de Dormir en el Retry Backoff** | Adquirir el permiso del semáforo *fuera* del bucle de reintentos (`acquire -> retryLoop(call+sleep) -> release`) | Retener un permiso escaso mientras el hilo duerme en el backoff de un `429`/`503` bloquea a los demás encargos sanos y hunde el throughput. Liberamos el permiso en `finally` antes de dormir (`Test #3`). |
| **3. Planificación Justa Multi-Tenant (`FairTenantScheduler` Round-Robin)** | Procesar los encargos en el orden crudo retornado por base de datos (FIFO) | El tamaño de las firmas es altamente sesgado (`~40` mediana vs. `~40,000` máxima). Un FIFO permite que la firma de `40,000` encargos monopolice el servicio downstream por horas (*Head-of-Line Blocking*). El Round-Robin asegura que las firmas medianas terminen en minutos (`Test #5`). |
| **4. Reclamo Atómico Previo de Idempotencia + Doble Verificación de Retiro** | Verificar idempotencia solo al final o no verificar retiro de versión a mitad del lote | Como cada llamada cuesta ~1 minuto de capacidad escasa, reclamamos `(templateId, targetVersion, engagementId)` antes de despachar (`Test #2`) y validamos `TemplateVersionLifecyclePort` antes y después de la cola del semáforo (`Test #6`). |

---

## 4. Qué Dejamos Fuera del Alcance Deliberadamente

1. **Motor de Fusión/Aplicación del Contenido de la Plantilla:** Explícitamente fuera del alcance según el enunciado.
2. **Clientes Reales del SDK de AWS y Testcontainers:** Omitidos a propósito para que los evaluadores puedan clonar el repositorio y ejecutar toda la suite de concurrencia, resiliencia y Spring Boot en `< 3 segundos` sin credenciales de AWS ni contenedores Docker.
3. **Expiración de Lease Distribuido (`TTL`) para Reclamos `IN_PROGRESS` Huérfanos:** Omitido en el adaptador en memoria por simplicidad; en el adaptador productivo de DynamoDB, cada reclamo `IN_PROGRESS` incluye `lease_expires_at = now + 3m` para recuperar tareas de pods que mueran inesperadamente.

---

## 5. Cómo Verificar, Ejecutar Pruebas y Correr la Simulación Interactiva (Cero Configuración Manual)

Este repositorio incluye el **Gradle Wrapper (`9.8.2`)** y el plugin **Foojay Toolchain Resolver (`org.gradle.toolchains.foojay-resolver-convention`)**. Cualquier evaluador de Caseware puede clonar cualquiera de las ramas (`main`, `feature/template-publish-fan-out-worker-java21` o `feature/template-publish-fan-out-worker-java25`) y ejecutar las pruebas directamente; Gradle descargará automáticamente el JDK correspondiente (Java 21 o Java 25) si no está instalado en la máquina.

### A. Ejecutar la Suite Automatizada de Concurrencia, Fallos y Spring Boot
```bash
# Linux / macOS
./gradlew test

# Windows (PowerShell / CMD)
.\gradlew.bat test
```

### B. Ejecutar la Simulación Interactiva (Por Defecto en `bootRun` / IntelliJ `Run`)
Por defecto, `src/main/resources/application.yml` define `caseware.fanout.simulation.enabled: true` para que cualquier evaluador pueda observar de inmediato el `TemplatePublishFanOutWorker` procesando un lote en vivo en la consola (demostrando planificación justa Round-Robin entre firmas, recuperación ante HTTP `429`, envío a DLQ, idempotencia ante re-entrega de eventos y métricas de Micrometer) sin necesidad de banderas adicionales:
- **En IntelliJ IDEA Ultimate:** Abre [`CasewareFanOutWorkerApplication.java`](src/main/java/com/caseware/fanout/CasewareFanOutWorkerApplication.java) y dale clic directamente al botón verde **Run (`▶`)** junto al método `main()`.
- **Por Consola Gradle:**
```bash
# Linux / macOS
./gradlew bootRun

# Windows (PowerShell / CMD)
.\gradlew.bat bootRun
```

### C. Levantar el Servicio SIN la Simulación (Modo Estándar de Producción)
Para iniciar el contexto de Spring Boot con la simulación desactivada (por ejemplo, al conectar un adaptador real de entrada de AWS SQS o correr en modo servicio estándar), basta con cambiar `caseware.fanout.simulation.enabled: false` en [`application.yml`](src/main/resources/application.yml) o pasar el argumento por línea de comandos / *Program Arguments* en la configuración de ejecución de IntelliJ:
```bash
# Linux / macOS
./gradlew bootRun --args='--caseware.fanout.simulation.enabled=false'

# Windows (PowerShell / CMD)
.\gradlew.bat bootRun --args="--caseware.fanout.simulation.enabled=false"
```
*(Nota: `@SpringBootTest` establece automáticamente `caseware.fanout.simulation.enabled=false` para mantener las pruebas automatizadas aisladas).*

