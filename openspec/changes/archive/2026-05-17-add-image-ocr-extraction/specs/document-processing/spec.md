## ADDED Requirements

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

## REMOVED Requirements

### Requirement: Images (PNG/JPEG) fail until OCR is available
**Reason**: OCR is now implemented, so PNG/JPEG documents are extracted instead of being marked `FAILED`. The new requirement "Plain text is extracted from images via OCR" replaces this behavior.
**Migration**: Documents previously marked `FAILED` with `errorMessage = "OCR not implemented yet"` can be reprocessed by re-publishing their `DocumentUploaded` event (or equivalent processing trigger) once OCR is deployed.
