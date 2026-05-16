## Why

Hoy el pipeline tiene un `documentId` y bytes guardados en MinIO, pero no extrae nada del documento: el `status` queda en `UPLOADED` para siempre. Fase 3 del roadmap arranca la cadena de valor real del producto — convertir documentos en texto plano que después (Fase 5) se transforma en datos estructurados. Sin esta capacidad, la app no demuestra todavía el verdadero objetivo del pipeline.

Adicionalmente, esta change anticipa la migración a Kafka (Fase 6/7): el procesamiento se dispara mediante un evento publicado tras el upload. Hoy el evento viaja por `ApplicationEventPublisher` de Spring; mañana se reemplaza por un productor Kafka sin tocar la lógica del extractor.

## What Changes

- **Nueva capacidad de procesamiento asincrónico in-process**: tras un upload exitoso, `DocumentUploadService` publica `DocumentUploadedEvent`; un `@Async @EventListener` lo consume y procesa el documento en background. La API sigue respondiendo rápido con `UPLOADED`.
- **Extracción de texto para PDF y Word**: nueva interfaz `DocumentTextExtractor` con dos implementaciones: `PdfTextExtractor` (Apache PDFBox 3.x), `WordTextExtractor` (Apache POI, soporta `.doc` y `.docx`). Un registry decide qué extractor usar según `contentType`.
- **Flujo de estados real**: `UPLOADED` → `PROCESSING` → `PROCESSED` (con `processedAt`) | `FAILED` (con `errorMessage`).
- **Nueva tabla `document_result`** (migración Flyway V2) con `document_id` (PK/FK a `document`), `raw_text TEXT`, `document_type VARCHAR NULL`, `extracted_data JSONB NULL`, `created_at TIMESTAMPTZ`. Fase 3 llena `raw_text`; las columnas restantes quedan listas para Fase 5.
- **`ObjectStorage` se amplía** con `InputStream read(String key)` para que el listener pueda descargar los bytes del documento desde MinIO.
- **Imágenes (PNG/JPEG) → `FAILED`** con `errorMessage = "OCR not implemented yet"`. Cuando llegue Fase 4 (OCR), esos documentos se podrán reprocesar.
- **Sin cambios en los contratos HTTP existentes**: `POST /documents`, `GET /documents/{id}` y `GET /documents/{id}/status` mantienen exactamente la misma forma. El `GET /documents/{id}` sigue **sin** exponer `rawText` (eso entra en Fase 5 cuando el documento esté completamente procesado y se exponga `DocumentResult`).

Fuera de scope explícito: OCR (Fase 4), data extraction estructurada con regex (Fase 5), migración a Kafka real (Fase 6/7), endpoints para descargar `rawText`, publicación de `DocumentProcessed/Failed` hacia consumidores externos.

## Capabilities

### New Capabilities

- `document-processing`: Flujo asincrónico de procesamiento que toma documentos recién subidos, descarga sus bytes desde object storage, extrae texto plano según el tipo de archivo y persiste el resultado, actualizando el estado del documento.

### Modified Capabilities

- `document-storage`: La abstracción `ObjectStorage` se amplía con una operación de **lectura** por key. Hasta ahora solo soportaba `store` y `delete`; el flujo de procesamiento necesita leer los bytes del documento.

## Impact

- **Código nuevo**:
  - `document_processing.tobias_moreno.document.event` — `DocumentUploadedEvent` (record).
  - `document_processing.tobias_moreno.document.processing` — `DocumentTextExtractor` (interfaz), `PdfTextExtractor`, `WordTextExtractor`, `TextExtractorRegistry`, `TextExtractionException`, `DocumentProcessingListener`, `DocumentProcessingService` (orquesta status transitions + extract + persistir result).
  - `document_processing.tobias_moreno.document.result` — `DocumentResult` (entity), `DocumentResultRepository`.
  - Config para `@EnableAsync` con `ThreadPoolTaskExecutor` acotado.
- **Cambios a código existente**:
  - `DocumentUploadService`: publica `DocumentUploadedEvent` tras el save (sin tx con el listener — el listener arranca después del commit).
  - `ObjectStorage` interfaz + `MinioObjectStorage`: nuevo método `read(String key)`. Tests mock-friendly.
- **Dependencias nuevas**: `org.apache.pdfbox:pdfbox:3.0.x`, `org.apache.poi:poi-ooxml:5.4.x` (trae `poi`, `poi-ooxml-lite`), `org.apache.poi:poi-scratchpad:5.4.x` (para `.doc` legacy via HWPF). Test: `org.awaitility:awaitility` para esperar al listener async.
- **Schema**: nueva migración `V2__create_document_result_table.sql`.
- **Config**: properties para el thread pool de procesamiento (`app.processing.async.core-size`, `max-size`, `queue-capacity`) con defaults sensatos.
- **Sin breaking changes** sobre Fase 1/2: APIs públicas idénticas. Documentos ya subidos quedarán en `UPLOADED` (no se reprocessan retroactivamente — es aceptable, no hay datos reales todavía).
- **Tests**: unit por extractor (PDF y Word con fixtures generados in-memory por PDFBox/POI), unit del listener con mocks, integration end-to-end con Testcontainers + Awaitility verificando happy path PDF, happy path DOCX y fallback PNG → FAILED.
