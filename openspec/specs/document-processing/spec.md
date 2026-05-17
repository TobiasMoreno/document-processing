## Purpose

Process uploaded documents asynchronously: extract plain text from supported formats (PDF, Word), persist the result, and advance the document through a clear status lifecycle. Decouples HTTP request handling from extraction work so the API stays responsive, and provides a contract that can be moved onto a distributed broker (e.g. Kafka) later without changing the extraction logic.

## Requirements

### Requirement: Successful upload triggers asynchronous processing
After a successful `POST /documents` (response `201 Created` with `status: UPLOADED`), the system SHALL trigger document processing without blocking the HTTP response. Processing SHALL occur on a separate execution thread.

#### Scenario: Upload returns immediately while processing runs in background
- **WHEN** a client uploads a valid document via `POST /documents`
- **THEN** the response is returned with `status: UPLOADED` before any text extraction begins
- **AND** processing of that document subsequently runs on a background worker

#### Scenario: Multiple uploads are processed concurrently
- **WHEN** multiple valid documents are uploaded in quick succession
- **THEN** each one is processed independently on the background worker pool

### Requirement: Document status transitions through processing lifecycle
A document's `status` SHALL transition `UPLOADED` → `PROCESSING` when processing starts, then to `PROCESSED` on success or `FAILED` on error. `processedAt` SHALL be set when the document reaches `PROCESSED` or `FAILED`.

#### Scenario: Successful PDF reaches PROCESSED
- **WHEN** a valid PDF is uploaded and the background processing completes successfully
- **THEN** the document's status becomes `PROCESSED` (transitioning through `PROCESSING`)
- **AND** `processedAt` is populated

#### Scenario: Failure transitions to FAILED with safe error message
- **WHEN** the background processing of a document raises an unrecoverable error
- **THEN** the document's status becomes `FAILED`
- **AND** `errorMessage` is set to a short, safe description (no stack traces, no internal paths)
- **AND** `processedAt` is populated

### Requirement: Status transition to PROCESSING is idempotent
The transition `UPLOADED` → `PROCESSING` SHALL be performed atomically and SHALL be a no-op when the document is no longer in `UPLOADED`. Duplicate processing events for the same document SHALL NOT cause double-processing.

#### Scenario: Duplicate event after successful processing is ignored
- **WHEN** a processing event for an already-`PROCESSED` document is delivered again
- **THEN** no new processing occurs
- **AND** the document remains in `PROCESSED`

### Requirement: Plain text is extracted from PDF and Word documents
For documents whose content type is `application/pdf`, `application/msword`, or `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, the system SHALL extract plain text and persist it as the document's `rawText`.

#### Scenario: Text extracted from PDF
- **WHEN** a PDF containing visible text "INVOICE 0001" is uploaded and processed
- **THEN** the persisted `rawText` contains the substring "INVOICE 0001"

#### Scenario: Text extracted from DOCX
- **WHEN** a `.docx` containing visible text "Hello world" is uploaded and processed
- **THEN** the persisted `rawText` contains the substring "Hello world"

### Requirement: Extraction result is persisted in a dedicated table
The extracted text SHALL be persisted in a `document_result` row whose `document_id` matches the source document. Columns `document_type` and `extracted_data` SHALL exist in the schema with NULLs allowed and SHALL remain NULL after Fase 3 (they are populated by later phases). The row SHALL be created only when extraction succeeds.

#### Scenario: Result row written on success
- **WHEN** a document is successfully processed
- **THEN** a `document_result` row exists with the same id, non-empty `raw_text`, and `created_at` set
- **AND** `document_type` and `extracted_data` are NULL

#### Scenario: No result row when processing fails
- **WHEN** processing of a document fails
- **THEN** no `document_result` row is created for that document

### Requirement: Plain text is extracted from images via OCR
For documents whose content type is `image/png` or `image/jpeg`, the system SHALL extract plain text using an OCR engine and persist it as the document's `rawText`. OCR failures SHALL be treated as extraction failures and follow the standard `FAILED` transition with a safe `errorMessage`.

#### Scenario: Text extracted from PNG
- **WHEN** a PNG containing visible text "HELLO OCR" is uploaded and processed
- **THEN** the document's status becomes `PROCESSED`
- **AND** the persisted `rawText` contains the substring "HELLO OCR"

#### Scenario: Text extracted from JPEG
- **WHEN** a JPEG containing visible text "INVOICE 0001" is uploaded and processed
- **THEN** the document's status becomes `PROCESSED`
- **AND** the persisted `rawText` contains the substring "INVOICE 0001"

#### Scenario: OCR engine failure transitions to FAILED
- **WHEN** the OCR engine raises an unrecoverable error while processing an image
- **THEN** the document's status becomes `FAILED`
- **AND** `errorMessage` is set to a short, safe description (no stack traces, no internal paths)
- **AND** no `document_result` row is created for that document

### Requirement: OCR engine is pluggable behind an abstraction
The OCR engine SHALL be invoked through an `OcrService` abstraction. The image extractor SHALL NOT depend on any concrete OCR SDK type directly, so that the local Tesseract implementation can be replaced (e.g. by a cloud OCR provider) without changes to the extractor or the processing pipeline.

#### Scenario: Image extractor delegates to OcrService
- **WHEN** the image extractor processes an image
- **THEN** it calls `OcrService.extractText(...)` and does not reference any concrete OCR SDK type in its own code

### Requirement: Processing reads bytes from object storage
The processing worker SHALL fetch the document bytes via the configured object storage abstraction, using the stored key, without depending on any concrete storage SDK type.

#### Scenario: Processing uses ObjectStorage to fetch the file
- **WHEN** processing starts for a document with storage key `2026/05/16/<uuid>`
- **THEN** the worker reads the object via `ObjectStorage` and not directly via the MinIO client in domain code

### Requirement: Document is classified into a known type after text extraction
After `rawText` is extracted, the system SHALL attempt to classify the document into a known `DocumentType`. When classification succeeds, the resolved `DocumentType` SHALL be persisted on the `document_result` row. When no extractor matches, `DocumentType` SHALL be persisted as `UNKNOWN`. Classification SHALL NOT affect the document `status`: a successfully text-extracted document remains `PROCESSED` regardless of classification outcome.

#### Scenario: Invoice PDF is classified as INVOICE
- **WHEN** a Factura C PDF (AFIP cód. 011) is uploaded and processed
- **THEN** the document's status becomes `PROCESSED`
- **AND** the persisted `document_result.document_type` equals `INVOICE`

#### Scenario: Unrecognized document is classified as UNKNOWN
- **WHEN** a document whose `rawText` does not match any registered data extractor is processed
- **THEN** the document's status becomes `PROCESSED`
- **AND** the persisted `document_result.document_type` equals `UNKNOWN`
- **AND** the persisted `document_result.extracted_data` is NULL

### Requirement: Structured data is extracted from Argentine Factura C invoices
For documents whose `rawText` matches the Factura C pattern (contains the literal `FACTURA`, at least one valid Argentine CUIT, and a parseable total amount), the system SHALL extract a structured invoice payload and persist it as JSON in `document_result.extracted_data`. The payload SHALL include the following fields when present in the source text: `invoiceNumber` (format `PV-CN`, e.g. `00001-00000001`), `issueDate` (ISO `yyyy-MM-dd`), `dueDate` (ISO `yyyy-MM-dd`), `subtotal` (decimal), `total` (decimal), `currency` (fixed `ARS` for Factura C), `issuerCuit`, `issuerName`, `customerCuit`, `customerName`, and `cae` (14 digits).

#### Scenario: Factura C fields are extracted and persisted as JSON
- **WHEN** a Factura C PDF with issueDate `25/02/2026`, invoice number `00001-00000001`, issuer CUIT `20428563787`, customer CUIT `20111111112`, total `850000,00`, and CAE `86096124599717` is processed
- **THEN** `document_result.extracted_data` is a JSON string whose parsed contents include `invoiceNumber = "00001-00000001"`, `issueDate = "2026-02-25"`, `total = 850000.00`, `currency = "ARS"`, `issuerCuit = "20428563787"`, `customerCuit = "20111111112"`, `cae = "86096124599717"`

#### Scenario: Currency for Factura C is ARS
- **WHEN** a Factura C is extracted
- **THEN** the resulting `currency` field equals `ARS`

### Requirement: Data extraction failures do not fail the document
If a data extractor matches a document but the extraction logic raises an unexpected exception OR the resulting payload fails Bean Validation, the system SHALL NOT transition the document to `FAILED` and SHALL NOT prevent the `document_result` row from being created with the already-extracted `rawText`. The failure SHALL be logged with the document id and the extractor name, and the document SHALL be persisted as if no extractor matched (`document_type = UNKNOWN`, `extracted_data = NULL`).

#### Scenario: Extractor exception leaves document PROCESSED with UNKNOWN type
- **WHEN** an extractor matches a document but raises an exception during field parsing
- **THEN** the document's status becomes `PROCESSED`
- **AND** `document_result.raw_text` is populated with the extracted text
- **AND** `document_result.document_type` equals `UNKNOWN`
- **AND** `document_result.extracted_data` is NULL

#### Scenario: Validation failure on extracted payload yields UNKNOWN
- **WHEN** the invoice extractor produces a payload that fails Bean Validation (e.g. missing required field, invalid CUIT format)
- **THEN** `document_result.document_type` equals `UNKNOWN`
- **AND** `document_result.extracted_data` is NULL
- **AND** the document's status is `PROCESSED`

### Requirement: Data extractors are pluggable behind an abstraction
The system SHALL invoke data extraction through a `DocumentDataExtractor` abstraction discovered via Spring's component scanning. The `DocumentProcessingService` SHALL NOT reference any concrete extractor type directly, so new document types (receipts, contracts, etc.) can be added by introducing a new `DocumentDataExtractor` bean without modifying the processing pipeline.

#### Scenario: Adding a new extractor requires no pipeline changes
- **WHEN** a new `DocumentDataExtractor` bean is registered in the Spring context
- **THEN** the document processing pipeline picks it up automatically via the registry
- **AND** no changes to `DocumentProcessingService` are required

### Requirement: HTTP contracts remain unchanged
The existing `POST /documents`, `GET /documents/{id}`, and `GET /documents/{id}/status` endpoints SHALL remain backwards-compatible: same routes, same response shapes, same status codes. The `GET /documents/{id}` response SHALL NOT include `rawText`.

#### Scenario: GET response shape is unchanged
- **WHEN** a client requests `GET /documents/{id}` after processing has completed
- **THEN** the response body contains exactly the same field set as before this change (no new fields exposed in this phase)
