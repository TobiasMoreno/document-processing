## 1. Dependencies

- [ ] 1.1 Add `org.apache.pdfbox:pdfbox:3.0.3` (or latest 3.0.x) to `pom.xml`
- [ ] 1.2 Add `org.apache.poi:poi-ooxml:5.4.0` (brings `poi`, `poi-ooxml-lite`)
- [ ] 1.3 Add `org.apache.poi:poi-scratchpad:5.4.0` (for legacy `.doc` via HWPF)
- [ ] 1.4 Add `org.awaitility:awaitility:4.3.0` as test scope (for async assertions)
- [ ] 1.5 Verify the build still resolves and compiles green after dependency changes

## 2. Schema migration

- [ ] 2.1 Create `src/main/resources/db/migration/V2__create_document_result_table.sql`
- [ ] 2.2 Table `document_result` with columns: `document_id UUID PRIMARY KEY REFERENCES document(id) ON DELETE CASCADE`, `raw_text TEXT NOT NULL`, `document_type VARCHAR(64)`, `extracted_data JSONB`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- [ ] 2.3 Verify migration applies cleanly via `docker compose up -d` + app start

## 3. Storage read capability

- [ ] 3.1 Add `InputStream read(String key)` to `ObjectStorage` interface
- [ ] 3.2 Implement `read` in `MinioObjectStorage` using `client.getObject(GetObjectArgs)`; map `NoSuchKey` to `StorageException` (do NOT swallow — caller must see the error)
- [ ] 3.3 Unit-test the read happy path in integration test (existing infra)

## 4. Domain layer: DocumentResult

- [ ] 4.1 Create `DocumentResult` entity (package `document_processing.tobias_moreno.document.result`) with `documentId` (UUID, `@Id`, `@MapsId` or just UUID PK), `rawText`, `documentType` (nullable), `extractedData` (nullable, store as String for now — JSONB mapping comes in Fase 5), `createdAt`
- [ ] 4.2 Create `DocumentResultRepository extends JpaRepository<DocumentResult, UUID>`

## 5. Extractor abstraction

- [ ] 5.1 Create package `document_processing.tobias_moreno.document.processing`
- [ ] 5.2 Define `DocumentTextExtractor` interface with `boolean supports(String contentType)` and `String extract(InputStream in) throws TextExtractionException`
- [ ] 5.3 Create `TextExtractionException` (and subtype `UnsupportedDocumentTypeException`)
- [ ] 5.4 Implement `PdfTextExtractor` using PDFBox 3.x `Loader.loadPDF(...)` + `PDFTextStripper`; wrap IOExceptions in `TextExtractionException`
- [ ] 5.5 Implement `WordTextExtractor` supporting both `application/msword` (HWPF `WordExtractor`) and `application/vnd.openxmlformats-officedocument.wordprocessingml.document` (XWPFWordExtractor)
- [ ] 5.6 Create `TextExtractorRegistry` that auto-collects all `DocumentTextExtractor` beans and dispatches by `supports(contentType)`; throws `UnsupportedDocumentTypeException` when none match

## 6. Async processing

- [ ] 6.1 Add `@EnableAsync` configuration class with a dedicated `ThreadPoolTaskExecutor` (core=2, max=4, queue=50, prefix `doc-proc-`, rejection `CallerRunsPolicy`)
- [ ] 6.2 Add `@ConfigurationProperties("app.processing.async")` with `coreSize`, `maxSize`, `queueCapacity` (defaults match step 6.1)
- [ ] 6.3 Create `DocumentUploadedEvent` record (`UUID documentId`) under `document_processing.tobias_moreno.document.event`

## 7. Processing service + listener

- [ ] 7.1 Add `DocumentRepository.markAsProcessing(UUID id)`: `@Modifying @Query` UPDATE with `WHERE id=? AND status='UPLOADED'`, returns `int` rows affected
- [ ] 7.2 Add `DocumentRepository.markAsProcessed(UUID id, Instant at)` and `markAsFailed(UUID id, String errorMessage, Instant at)` (also `@Modifying` for direct SQL, avoiding entity load)
- [ ] 7.3 Create `DocumentProcessingService.process(UUID documentId)`:
  - CAS UPLOADED → PROCESSING; if 0 rows, return (idempotent skip)
  - Load Document (for storagePath + contentType)
  - Try-with-resources: `try (InputStream in = objectStorage.read(storagePath)) { String text = registry.dispatch(contentType, in); }`
  - Persist `DocumentResult(documentId, rawText=text)`
  - Mark document as PROCESSED with `processedAt = Instant.now()`
  - On `UnsupportedDocumentTypeException` with image content-types → mark FAILED with `errorMessage = "OCR not implemented yet"`
  - On `UnsupportedDocumentTypeException` otherwise → "Unsupported document type"
  - On `TextExtractionException` → "Failed to extract text"
  - On any other Throwable → "Processing failed" (and log full stack)
- [ ] 7.4 Create `DocumentProcessingListener` with `@Async @EventListener` on `DocumentUploadedEvent` delegating to `DocumentProcessingService.process(...)`; catch and log any throwable to keep the executor healthy

## 8. Wire upload → event

- [ ] 8.1 Inject `ApplicationEventPublisher` into `DocumentUploadService`
- [ ] 8.2 Publish `DocumentUploadedEvent(document.getId())` after `repository.save(document)` succeeds

## 9. Tests — unit

- [ ] 9.1 Unit test `PdfTextExtractor.extract`: build an in-memory PDF with PDFBox containing known text, run extractor, assert text present
- [ ] 9.2 Unit test `PdfTextExtractor` on corrupted bytes → `TextExtractionException`
- [ ] 9.3 Unit test `WordTextExtractor` for `.docx` (build via `XWPFDocument` in-memory)
- [ ] 9.4 Unit test `WordTextExtractor` for `.doc` (skip if HWPF in-memory generation is cumbersome — use a tiny binary fixture in `src/test/resources/fixtures/sample.doc` only if needed)
- [ ] 9.5 Unit test `TextExtractorRegistry`: dispatches correctly; throws `UnsupportedDocumentTypeException` for `image/png`
- [ ] 9.6 Unit test `DocumentProcessingService.process` happy path with mocks (CAS returns 1, extractor returns text, repository saves result, status moves to PROCESSED)
- [ ] 9.7 Unit test `DocumentProcessingService.process` when CAS returns 0 → no-op, no calls to storage or extractor
- [ ] 9.8 Unit test `DocumentProcessingService.process` when extractor throws → status moves to FAILED with categorized errorMessage, no DocumentResult row saved
- [ ] 9.9 Unit test PNG path → status FAILED with `errorMessage = "OCR not implemented yet"`

## 10. Tests — integration

- [ ] 10.1 Extend `Fixtures` with a real PDF generated via PDFBox containing the literal text "INVOICE 0001" (so the assertion is meaningful)
- [ ] 10.2 Extend `Fixtures` with a real DOCX generated via POI containing "Hello world"
- [ ] 10.3 Integration test: upload PDF → wait (Awaitility, max 10s) for status PROCESSED → assert `document_result.raw_text` contains "INVOICE 0001"
- [ ] 10.4 Integration test: upload DOCX → wait for PROCESSED → assert `raw_text` contains "Hello world"
- [ ] 10.5 Integration test: upload PNG → wait for FAILED → assert `errorMessage = "OCR not implemented yet"` and no `document_result` row exists
- [ ] 10.6 Integration test: HTTP contracts unchanged — `GET /documents/{id}` body shape identical to before this change (no `rawText` field), `POST /documents` still returns 201 immediately
- [ ] 10.7 Ensure existing upload integration tests still pass (no regression)

## 11. Verification

- [ ] 11.1 `./mvnw test` green on the full suite
- [ ] 11.2 `docker compose up -d` + `./mvnw spring-boot:run`; upload a real PDF; observe status transition to PROCESSED via `GET /documents/{id}/status`; query `document_result` in Postgres to confirm `raw_text` is populated
- [ ] 11.3 Upload a PNG; confirm status reaches FAILED with the expected errorMessage
- [ ] 11.4 `openspec validate document-text-extraction --strict` passes
