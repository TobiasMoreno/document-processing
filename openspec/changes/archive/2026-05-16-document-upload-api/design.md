## Context

Fase 1 del Document Intelligence Pipeline (ver `document-intelligence-pipeline-planning.md`). El repo contiene un skeleton de Spring Boot (commit `22074e6`). No hay specs previas en `openspec/specs/`, no hay otras capabilities. Esta change es greenfield para `document-upload` y `document-storage`.

Constraints:
- Java 21, Spring Boot.
- Monolito en este repo, organizado en módulos preparado para partir en `document-api` / `document-worker` en Fase 7 (dentro del mismo repo, opción 2a confirmada por el usuario).
- API abierta (sin auth en esta fase).
- MinIO como storage desde día 1 (no filesystem local), para evitar migración futura.
- PostgreSQL para metadata.
- Sin Kafka todavía: el endpoint responde de forma sincrónica con `UPLOADED` y termina ahí (el evento `DocumentUploaded` se agrega en Fase 6).

Stakeholder único: el dueño del proyecto, practicando criterio backend.

## Goals / Non-Goals

**Goals:**
- Endpoint `POST /documents` que devuelve rápido con un `documentId` estable y estado `UPLOADED`.
- Validación de archivo (tamaño + content-type) antes de cualquier I/O contra storage.
- Persistencia atómica: si el upload a MinIO falla, no queda fila huérfana en Postgres; si el insert a Postgres falla, no queda objeto huérfano en MinIO (o queda flaggeado para limpieza).
- Abstracción de storage detrás de una interfaz para poder cambiar a S3/Textract sin tocar el dominio.
- Schema versionado con Flyway desde la primera migración.
- Local dev environment reproducible vía `docker-compose up`.

**Non-Goals:**
- GET endpoints de consulta (Fase 2).
- Eventos Kafka, outbox, idempotencia por `eventId` (Fase 6/8).
- Extracción de texto o datos (Fase 3-5).
- Worker / procesamiento asincrónico (Fase 7).
- Autenticación, rate limiting, multi-tenant.
- Persistencia de `DocumentResult` (Fase 5).
- Modularización física `document-api`/`document-worker` (Fase 7).

## Decisions

### 1. ID del documento: UUID v4 generado por la app
**Decisión**: `documentId` es `UUID` generado en el servicio antes de persistir.
**Alternativas**: secuencia Postgres (`BIGSERIAL`), ULID.
**Por qué**: UUID se puede generar antes de tocar la DB → permite usarlo como key del objeto en MinIO y como `documentId` en la respuesta sin un round-trip extra. ULID sería mejor para ordering, pero UUID alcanza y es lo más estándar en el stack Spring.

### 2. Orden de operaciones: validar → subir a MinIO → insertar en Postgres
**Decisión**:
1. Validar tamaño + content-type (rechazo rápido sin I/O).
2. Generar `UUID` + computar `storagePath` (`{bucket}/{yyyy}/{MM}/{dd}/{uuid}`).
3. Subir bytes a MinIO.
4. Insertar fila `Document` en Postgres con `status=UPLOADED`.
5. Si paso 4 falla → intentar borrar el objeto en MinIO (best-effort, log si falla); devolver `500`.

**Alternativas**:
- Insertar primero, luego subir: si el upload falla quedan filas huérfanas que ensucian queries.
- Two-phase commit / Saga: overkill para Fase 1.

**Por qué**: minimiza basura visible al cliente. La basura posible (objeto en MinIO sin row) se limpia con un job de garbage collection (Fase 9, no ahora). El estado consultable nunca tiene filas sin objeto.

### 3. Layout del objeto en MinIO
**Decisión**: key = `{yyyy}/{MM}/{dd}/{documentId}` (sin extensión). El `contentType` real se guarda como metadata del objeto en MinIO **y** en la columna `contentType` de Postgres (fuente de verdad: Postgres).
**Por qué**: partición por fecha facilita lifecycle policies y listing. Sin extensión evita confusión cuando el usuario sube un PDF con `.docx` (el content-type detectado manda).

### 4. Detección de content-type
**Decisión**: usar el `Content-Type` del multipart **y** validarlo contra Apache Tika sniffing de los primeros bytes. Si discrepan → rechazo `400`.
**Alternativas**: confiar solo en el header (rápido pero engañable), solo Tika (más lento pero seguro).
**Por qué**: el header del cliente es trivialmente falsificable. Tika es la dependencia estándar y agregarla ahora ahorra rework en Fase 3 (PDFBox/POI ya integran con Tika).

### 5. Schema Postgres
**Decisión**:
```sql
CREATE TABLE document (
  id              UUID PRIMARY KEY,
  original_filename VARCHAR(255) NOT NULL,
  content_type    VARCHAR(127) NOT NULL,
  size_bytes      BIGINT NOT NULL,
  storage_path    VARCHAR(512) NOT NULL,
  status          VARCHAR(32) NOT NULL,
  error_message   VARCHAR(1024),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at    TIMESTAMPTZ
);
CREATE INDEX idx_document_status ON document(status);
CREATE INDEX idx_document_created_at ON document(created_at DESC);
```
`status` como `VARCHAR` (no enum nativo) → más fácil agregar valores en fases siguientes sin migración de tipo.

**Sobre `DocumentResult` / JSONB**: no se crea en esta change. Se modela en Fase 5. La decisión "JSONB" queda anotada acá pero no genera tabla todavía. Evitamos sobre-diseñar.

### 6. Abstracción de storage
**Decisión**: interfaz `ObjectStorage` con métodos `store(key, inputStream, size, contentType)` y `delete(key)` (para rollback). Implementación `MinioObjectStorage`.
**Por qué**: aislamos el dominio del cliente concreto; mañana podemos meter `S3ObjectStorage` sin tocar el service. Mantenemos la interfaz **mínima** — sin `get`, sin `presignedUrl` (eso entra cuando lo necesitemos en Fase 2 o 7).

### 7. Migrations con Flyway (no `ddl-auto`)
**Decisión**: `spring.jpa.hibernate.ddl-auto=validate`. Schema gestionado por Flyway desde `V1__create_document_table.sql`.
**Por qué**: práctica estándar productiva. `ddl-auto=update` es trampa que se cobra cuando hay datos reales.

### 8. Validación de tamaño en dos capas
**Decisión**:
- Servlet: `spring.servlet.multipart.max-file-size=10MB` y `max-request-size=12MB` (margen para boundaries).
- App: re-chequeo explícito de `file.getSize() > 10 * 1024 * 1024` antes de subir.
**Por qué**: el límite del servlet aborta con `MultipartException` (poco amigable); el chequeo en app permite respuesta `400` consistente con el resto de las validaciones.

### 9. Respuesta de error
**Decisión**: cuerpo JSON `{ "error": "<code>", "message": "<safe message>" }`. Códigos: `INVALID_CONTENT_TYPE`, `FILE_TOO_LARGE`, `EMPTY_FILE`, `STORAGE_ERROR`, `INTERNAL_ERROR`. Implementado vía `@RestControllerAdvice`.
**Por qué**: clientes pueden discriminar sin parsear strings. No exponemos stack traces ni paths internos.

### 10. Docker Compose
**Decisión**: `docker-compose.yml` con:
- `postgres:16-alpine` (puerto 5432, volumen named, healthcheck).
- `minio/minio:latest` (puertos 9000 API + 9001 console, volumen named, healthcheck).
- `minio-init` (servicio one-shot con `mc` que crea el bucket si no existe, depende de minio healthy).
**Por qué**: levantar todo con `docker-compose up -d` sin pasos manuales.

## Risks / Trade-offs

- **Objeto huérfano en MinIO si crash entre paso 3 y 4** → mitigación: rollback best-effort con `delete` + log; GC job planificado para Fase 9. Acceptable porque MinIO no es de pago por objeto y los huérfanos no son visibles vía API.
- **Tika sniffing sobre el stream consume memoria** → mitigación: leer solo los primeros N KB para el sniff, no el archivo entero. Spring `MultipartFile` ya buffea en disco si supera el umbral, así que el costo extra es bajo.
- **Sin auth → cualquiera puede llenar el bucket** → aceptable en Fase 1 (entorno de práctica), pero documentado como deuda para no olvidar antes de exponer.
- **Estado `UPLOADED` sin worker que avance el ciclo** → en Fase 1 los documentos quedan en `UPLOADED` indefinidamente. Es esperado; el worker llega en Fase 7. No conviene meter código de "fake processing" para no ensuciar.
- **`document-upload` y `document-storage` como capabilities separadas** → puede parecer over-engineering para Fase 1, pero la separación paga en Fase 7 (worker reusa `document-storage` para leer, no para escribir) y Fase 9 (métricas de storage independientes).

## Migration Plan

- No hay datos previos. Primera migración Flyway `V1__create_document_table.sql` se aplica al primer `bootRun`/`spring-boot:run`.
- Rollback: `docker-compose down -v` para resetear todo el entorno local. No aplica rollback productivo (no hay prod).

## Open Questions

- ¿Naming del bucket? Propuesta: `documents` (configurable vía `app.storage.minio.bucket`). Confirmar en implementación si surge alguna restricción.
- ¿Conviene exponer la `Console MinIO` (puerto 9001) en `docker-compose` por defecto? Propuesta: sí, en local; queda como dev affordance.
