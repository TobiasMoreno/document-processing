## Context

Fase 2 del Document Intelligence Pipeline (ver `document-intelligence-pipeline-planning.md`). Sobre la base de Fase 1 ya implementada:
- Entity `Document` en `document_processing.tobias_moreno.document.Document` con todos los campos necesarios.
- `DocumentRepository extends JpaRepository<Document, UUID>` ya provee `findById(UUID)`.
- `DocumentController` ya tiene `POST /documents`.
- `GlobalExceptionHandler` ya mapea las excepciones del upload a respuestas `{error, message}`.

Constraints: Java 21, Spring Boot 4.0.6, sin Kafka, sin auth.

## Goals / Non-Goals

**Goals:**
- Endpoints `GET` rápidos, read-only, sin tocar storage.
- Proyección segura: el cliente nunca ve `storagePath`.
- Errores con el mismo shape `{error, message}` que ya usa la app, sin filtrar internals.
- Cero cambios al flujo de upload existente y a sus tests.

**Non-Goals:**
- Listar documentos (`GET /documents` sin id).
- Filtros, paginación, ordenamiento.
- `GET /documents/{id}/result` (Fase 5).
- Descargar el archivo original o presigned URLs (Fase posterior si se necesita).
- Eventos Kafka asociados a la consulta.

## Decisions

### 1. Capability nueva (`document-query`) en lugar de modificar `document-upload`
**Decisión**: crear una capability separada.
**Alternativa considerada**: ampliar `document-upload` con requirements de consulta (mismo recurso REST `/documents`).
**Por qué**: `document-upload` describe la creación (invariantes de validación, atomicidad, generación de id). La consulta tiene invariantes diferentes (idempotencia, read-only, semántica 404). Separar deja cada spec con un foco claro y evita un archivo gigante en Fases siguientes. El costo (dos archivos en `specs/`) es bajo.

### 2. DTOs explícitos (no exponer la entity)
**Decisión**: `DocumentResponse` (record) con los campos visibles + `DocumentStatusResponse` (record) con `{ documentId, status }`. La entity `Document` queda interna; la conversión vive en un factory estático del propio DTO.
**Por qué**: previene exponer accidentalmente `storagePath` u otros campos que se agreguen en el futuro. Hacer el contrato explícito documenta qué ve el cliente.

### 3. Service nuevo `DocumentQueryService`
**Decisión**: lookup va por un service `DocumentQueryService.findById(UUID)` que devuelve `Document` o lanza `DocumentNotFoundException`. El controller mapea entity → DTO.
**Alternativa**: que el controller use el repository directo.
**Por qué**: deja el lugar natural donde, en Fase 5, se va a sumar el join con `DocumentResult` sin volver a romper el controller.

### 4. `DocumentNotFoundException` con manejo centralizado
**Decisión**: nueva `RuntimeException` en el paquete `document`. El `GlobalExceptionHandler` la mapea a `404` con `error="DOCUMENT_NOT_FOUND"`. Mensaje genérico ("Document not found"), no incluye el id solicitado en el body — el id ya viene en la URL del request, no aporta y simplifica el contrato.
**Por qué**: misma shape que el resto, sin leakage de internals. Centralizado para que aplique a ambos endpoints sin duplicación.

### 5. UUID malformado → `400 INVALID_DOCUMENT_ID`
**Decisión**: declarar `@PathVariable UUID id` en el controller. Spring lanza `MethodArgumentTypeMismatchException` si el segmento no es un UUID válido. El `GlobalExceptionHandler` agrega un handler para esa excepción **solo** cuando el parámetro requerido es de tipo `UUID` (chequea `e.getRequiredType()`); de lo contrario delega al manejo genérico para no afectar otros endpoints futuros.
**Alternativa descartada**: aceptar `String` y validar manualmente con `UUID.fromString` dentro del controller (más boilerplate y duplica la conversión).
**Por qué**: aprovecha el binding nativo de Spring, mantiene los handlers como única fuente de verdad para shapes de error.

### 6. Sin cache, sin proyecciones JPA custom
**Decisión**: el repository devuelve la entity completa con `findById`. No se hace projection ni cache.
**Por qué**: el volumen esperado es bajo en esta fase y la entity es chica. Optimizar antes de tener datos reales sería prematuro.

### 7. Sin transacción explícita
**Decisión**: no marcar el service con `@Transactional`. Spring Data JPA abre una tx implícita para `findById` y la cierra antes de que el controller mapee a DTO.
**Por qué**: lectura simple, sin lazy loading (todos los campos son básicos). Una `@Transactional(readOnly=true)` es válida pero no aporta nada concreto acá.

### 8. Logs
**Decisión**: log `DEBUG` cuando se consulta y se encuentra; sin log cuando es 404 (es un caso esperado, no error). El handler ya loguea ante errores genuinos.
**Por qué**: evita ruido en producción ante 404s que no son patológicos.

## Risks / Trade-offs

- **Sin auth → cualquiera con un `documentId` puede ver metadata** → aceptable en esta fase de práctica; ya está documentado como deuda desde Fase 1. Sin info crítica (no exponemos `storagePath`).
- **Enumeration attack sobre UUIDs** → 400 vs 404 podría usarse para discriminar formato vs existencia. Mitigación: ambas respuestas son explícitas y no leakean info útil dado que los UUIDs son aleatorios; un atacante no puede enumerar. Acceptable.
- **El status `UPLOADED` no avanza nunca en Fase 2** → esperado: el worker llega en Fase 7. El endpoint igualmente sirve para confirmar el upload.
- **DTO duplica campos de la entity** → leve duplicación, pero compra explicitness sobre qué se expone. Vale la pena.

## Migration Plan

- No hay datos previos relevantes ni cambio de schema. La change es aditiva al controller existente.
- Rollback: revertir el commit; los endpoints desaparecen sin efecto sobre upload.

## Open Questions

- Ninguna abierta al momento de escribir. Si en Fase 5 (`DocumentResult`) decidimos exponer el resultado en el mismo `GET /documents/{id}`, esta change deja el service como punto natural para sumar el join.
