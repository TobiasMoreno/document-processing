## ADDED Requirements

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
