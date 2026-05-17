## ADDED Requirements

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

### Requirement: Images (PNG/JPEG) fail until OCR is available
While OCR is not implemented, documents with content type `image/png` or `image/jpeg` SHALL be marked `FAILED` with `errorMessage = "OCR not implemented yet"`. They remain uploadable so that, once OCR ships, they can be reprocessed.

#### Scenario: PNG upload eventually fails with explicit message
- **WHEN** a PNG document is uploaded and processed
- **THEN** the document's status becomes `FAILED`
- **AND** `errorMessage` equals `OCR not implemented yet`

#### Scenario: No result row is created for failed image
- **WHEN** a PNG document fails because OCR is not implemented
- **THEN** no `document_result` row exists for that document

### Requirement: Processing reads bytes from object storage
The processing worker SHALL fetch the document bytes via the configured object storage abstraction, using the stored key, without depending on any concrete storage SDK type.

#### Scenario: Processing uses ObjectStorage to fetch the file
- **WHEN** processing starts for a document with storage key `2026/05/16/<uuid>`
- **THEN** the worker reads the object via `ObjectStorage` and not directly via the MinIO client in domain code

### Requirement: HTTP contracts remain unchanged
The existing `POST /documents`, `GET /documents/{id}`, and `GET /documents/{id}/status` endpoints SHALL remain backwards-compatible: same routes, same response shapes, same status codes. The `GET /documents/{id}` response SHALL NOT include `rawText`.

#### Scenario: GET response shape is unchanged
- **WHEN** a client requests `GET /documents/{id}` after processing has completed
- **THEN** the response body contains exactly the same field set as before this change (no new fields exposed in this phase)
