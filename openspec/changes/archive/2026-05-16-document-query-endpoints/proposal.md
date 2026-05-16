## Why

La Fase 1 (`document-upload-api`) deja un `documentId` como única salida visible al cliente. Sin un endpoint de consulta no se puede ver qué se subió, ni en qué estado quedó cada documento. Esta change cubre Fase 2 del roadmap: exponer la metadata y el estado de un documento por id, de forma que el cliente pueda confirmar qué se cargó y — en fases siguientes — saber cuándo terminó de procesarse.

## What Changes

- Nuevo endpoint `GET /documents/{id}` → `200` con un DTO que expone `documentId`, `originalFilename`, `contentType`, `sizeBytes`, `status`, `createdAt`, `updatedAt`, `processedAt`, `errorMessage`. **No** se expone `storagePath` (detalle interno del backend de storage).
- Nuevo endpoint `GET /documents/{id}/status` → `200` con `{ documentId, status }`.
- 404 con body `{ error: "DOCUMENT_NOT_FOUND", message }` cuando el id no existe en ambos endpoints.
- 400 con body `{ error: "INVALID_DOCUMENT_ID", message }` cuando el path variable no es un UUID válido.
- Ambos endpoints son read-only e idempotentes; no consultan ni leen el objeto en MinIO (solo metadata en Postgres).

Fuera de scope explícito: `GET /documents/{id}/result` (entra cuando exista `DocumentResult` en Fase 5), listado/paginación, filtros por status o fecha, eventos Kafka, descarga del archivo original.

## Capabilities

### New Capabilities

- `document-query`: Exposición read-only de la metadata y el estado de un documento previamente subido, por su identificador. Aísla las semánticas de consulta (idempotente, 404, sin tocar storage) del flujo de upload.

### Modified Capabilities

<!-- Ninguna. `document-upload` describe la creación; los endpoints de consulta no cambian sus requirements existentes. -->

## Impact

- **Código nuevo**: `DocumentQueryService` (lookup por id), `DocumentResponse` DTO (proyección segura), `DocumentStatusResponse` DTO. Nuevos `@GetMapping` en `DocumentController` (extender el controller existente). Nuevo `DocumentNotFoundException` y handler en `GlobalExceptionHandler`.
- **Manejo de `MethodArgumentTypeMismatchException`** (UUID malformado en `@PathVariable`) en el handler existente, mapeado a `400 INVALID_DOCUMENT_ID`.
- **Sin cambios de schema**: no se requiere migración Flyway; se usan los campos ya creados en `V1__create_document_table.sql`.
- **Sin dependencias nuevas**.
- **Sin breaking changes** sobre la Fase 1: `POST /documents` y su contrato no se tocan.
- **Tests**: unit del query service + integration con Testcontainers (Postgres + MinIO siguen exigidos por el contexto Spring), cubriendo happy path, 404 y 400.
