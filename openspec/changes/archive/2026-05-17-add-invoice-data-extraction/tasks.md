## 1. Domain model and DTOs

- [x] 1.1 Create `document/processing/data/DocumentType.java` enum with `INVOICE` and `UNKNOWN`
- [x] 1.2 Create `document/processing/data/ExtractedDocument.java` record (`DocumentType type`, `Object data`)
- [x] 1.3 Create `document/processing/data/InvoiceData.java` DTO with fields: `invoiceNumber`, `issueDate` (LocalDate), `dueDate` (LocalDate), `subtotal` (BigDecimal), `total` (BigDecimal), `currency` (String, default `ARS`), `issuerCuit`, `issuerName`, `customerCuit`, `customerName`, `cae`
- [x] 1.4 Add Bean Validation annotations on `InvoiceData` (`@NotBlank`, `@NotNull`, `@DecimalMin("0.0")`, `@Pattern` for CUIT `\d{11}` and CAE `\d{14}`)
- [x] 1.5 Verify `spring-boot-starter-validation` is on the classpath; add to `pom.xml` if missing
- [x] 1.6 Update `DocumentResult` entity/mapping to ensure `documentType` is persisted as `String` (via enum-to-string conversion) and `extractedData` as JSON string

## 2. Extractor abstraction and registry

- [x] 2.1 Create `document/processing/data/DocumentDataExtractor.java` interface with `Optional<ExtractedDocument> extract(String rawText)`
- [x] 2.2 Create `document/processing/data/DocumentDataExtractorRegistry.java` (Spring `@Component`) injecting `List<DocumentDataExtractor>` and exposing `Optional<ExtractedDocument> classify(String rawText, UUID documentId)` that loops extractors, catches exceptions, runs Bean Validation on the payload, and logs warnings on soft failures
- [x] 2.3 Inject `Validator` (Jakarta) into the registry to validate extracted payloads
- [x] 2.4 Unit test the registry: matching extractor returns its result; throwing extractor → empty Optional + warning logged; validation-failing payload → empty Optional + warning logged

## 3. Invoice extractor implementation

- [x] 3.1 Create `document/processing/data/InvoiceDataExtractor.java` implementing `DocumentDataExtractor`, registered as Spring `@Component`
- [x] 3.2 Implement `parseArAmount(String)` helper converting AR-formatted strings (`850000,00`, `1.234.567,89`) to `BigDecimal`
- [x] 3.3 Implement `parseArDate(String)` helper converting `dd/MM/yyyy` to `LocalDate`
- [x] 3.4 Implement `isValidCuit(String)` helper validating the 11-digit Argentine CUIT check digit
- [x] 3.5 Implement regex patterns and `extract(rawText)` method covering: `FACTURA` trigger, `Punto de Venta`/`Comp. Nro` concat, `Fecha de Emisión`, `Fecha de Vto. para el pago`, two `CUIT:` occurrences (issuer first, customer second), `Razón Social` (first), `Apellido y Nombre / Razón Social`, `Subtotal`, `Importe Total`, `CAE N°`
- [x] 3.6 Set `currency = "ARS"` unconditionally for matched Factura C
- [x] 3.7 Return `Optional.empty()` if the trigger conditions (literal `FACTURA` + valid CUIT + parseable total) are not all met

## 4. Pipeline integration

- [x] 4.1 Inject `DocumentDataExtractorRegistry` into `DocumentProcessingService`
- [x] 4.2 After successful text extraction, call `registry.classify(rawText, documentId)`; persist `documentType` (default `UNKNOWN` when empty) and `extractedData` (Jackson-serialized JSON string, or `null` when empty)
- [x] 4.3 Inject `ObjectMapper` for JSON serialization; on JSON serialization failure, fall back to `UNKNOWN` + null and log warning (do not fail the document)
- [x] 4.4 Confirm that data extraction failure does NOT change the `PROCESSED` status — update any existing assertions if needed

## 5. Unit tests for the invoice extractor

- [x] 5.1 Place `02-2026.pdf` under `src/test/resources/fixtures/factura-c.pdf`; extract rawText with `PdfTextExtractor` inside the test (replaces the static `.txt` fixture — keeps it in sync with PDFBox output)
- [x] 5.2 `InvoiceDataExtractorTest`: feed fixture → assert every field matches the expected value (invoiceNumber `00001-00000001`, issueDate `2026-02-25`, total `850000.00`, currency `ARS`, issuerCuit `20428563787`, customerCuit `20111111112`, cae `86096124599717`, etc.)
- [x] 5.3 `InvoiceDataExtractorTest`: text without `FACTURA` literal → `Optional.empty()`
- [x] 5.4 `InvoiceDataExtractorTest`: text with `FACTURA` but no valid CUIT → `Optional.empty()`
- [x] 5.5 `InvoiceDataExtractorTest`: amount parsing edge cases (`1.234.567,89` → `1234567.89`)
- [x] 5.6 `InvoiceDataExtractorTest`: CUIT validation rejects `00000000000` and accepts `20428563787`

## 6. Integration tests

- [x] 6.1 `DocumentProcessingIntegrationTest`: upload `02-2026.pdf` (place a copy under `src/test/resources/fixtures/`) → poll until `PROCESSED` → assert `document_result.document_type = "INVOICE"` and `extracted_data` JSON parses to expected `InvoiceData` fields
- [x] 6.2 `DocumentProcessingIntegrationTest`: covered by the existing `pdfUploadReachesProcessedWithExtractedText` test (PDF with text "INVOICE 0001" — no Factura C markers — now asserts `document_type = "UNKNOWN"` + `extracted_data IS NULL`)
- [~] 6.3 Skipped at integration level: the throwing-extractor / validation-failing-extractor paths are covered exhaustively in `DocumentDataExtractorRegistryTest`; replicating at integration adds no signal because the registry runs in-process either way

## 7. Verification and documentation

- [x] 7.1 Run `./mvnw test` and fix regressions — 68/68 passing (1 preexisting skip on Tesseract live test). Required adding `jackson-datatype-jsr310` + an explicit `ObjectMapper` bean since Spring Boot 4.0.6 ships Jackson 3 (`tools.jackson.*`) as the auto-configured ObjectMapper but the service uses the Jackson 2 (`com.fasterxml.jackson.*`) API.
- [x] 7.2 Update `document-intelligence-pipeline-planning.md` Fase 5 section to mark it done with summary of supported fields
- [x] 7.3 Manual smoke test via Postman: upload `02-2026.pdf`, hit `GET /documents/{id}/result` (if such endpoint exists, otherwise verify DB row directly) and confirm the JSON shape
