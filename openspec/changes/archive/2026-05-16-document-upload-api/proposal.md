## Why

El Document Intelligence Pipeline necesita un punto de entrada por HTTP para recibir documentos. Sin un endpoint de upload no hay flujo aguas abajo: ni metadata persistida, ni archivo almacenado, ni evento que disparar al worker. Esta change cubre Fase 1 del roadmap (ver `document-intelligence-pipeline-planning.md`) y deja la base sobre la que el resto del pipeline se va a construir: persistencia de metadata, almacenamiento del archivo original y un identificador estable (`documentId`) para correlacionar todo el procesamiento posterior.

## What Changes

- Nuevo endpoint `POST /documents` (multipart) que recibe un archivo, lo valida, lo sube a MinIO, persiste la metadata en PostgreSQL y responde `{ documentId, status: "UPLOADED" }`.
- Nueva entity JPA `Document` con campos `id`, `originalFilename`, `contentType`, `size`, `storagePath`, `status`, `createdAt`, `updatedAt`, `processedAt`, `errorMessage` y estados `UPLOADED | PROCESSING | PROCESSED | FAILED`.
- Validación de archivo: tamaño máximo **10 MB** y content-types permitidos: `application/pdf`, `application/msword`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, `image/png`, `image/jpeg`. Rechazo con `400 Bad Request` cuando no cumple.
- Storage de archivos en **MinIO** (S3-compatible) desde el día 1. Bucket configurable vía properties; el path guardado en `storagePath` apunta al objeto en MinIO.
- Persistencia en **PostgreSQL** vía Spring Data JPA. Schema gestionado con Flyway.
- `docker-compose.yml` con servicios `postgres` y `minio` (Kafka se agrega en Fase 6, no en esta change).
- Manejo de errores: tipo/tamaño inválido → `400`; fallo subiendo a MinIO o persistiendo → `500` sin exponer detalles internos.

Fuera de scope (fases posteriores): endpoints GET de consulta, Kafka/eventos, worker, extracción de texto/datos, idempotencia por `eventId`, Kubernetes.

## Capabilities

### New Capabilities

- `document-upload`: Recibe documentos vía HTTP multipart, valida tipo y tamaño, almacena el archivo en object storage y persiste su metadata, devolviendo un identificador estable para consulta y procesamiento posterior.
- `document-storage`: Abstracción sobre el almacenamiento de objetos (MinIO/S3-compatible) que expone subir y referenciar archivos por una clave estable; aísla el resto del sistema del backend concreto de storage.

### Modified Capabilities

<!-- No hay specs existentes en openspec/specs/. -->

## Impact

- **Código nuevo**: módulo único Spring Boot (organización Gradle/Maven preparada para partir en `document-api`/`document-worker` en Fase 7 dentro del mismo repo). Paquetes esperados: `document` (controller, service, entity, repository) y `storage` (cliente MinIO + abstracción).
- **Dependencias nuevas**: `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-data-jpa`, driver `postgresql`, `flyway-core` + `flyway-database-postgresql`, cliente oficial MinIO (`io.minio:minio`). Testing: `spring-boot-starter-test`, `testcontainers` (postgresql + minio/localstack).
- **Infra local**: `docker-compose.yml` con `postgres:16` y `minio/minio:latest` (+ inicialización de bucket).
- **Config**: properties para DB (`spring.datasource.*`), MinIO (`app.storage.minio.endpoint`, `access-key`, `secret-key`, `bucket`) y límites de upload (`spring.servlet.multipart.max-file-size`, `app.upload.allowed-content-types`).
- **API pública**: contrato nuevo `POST /documents`; sin auth en esta fase.
- **Sin breaking changes** (no hay API previa). El skeleton del commit `22074e6` se extiende, no se reescribe.
