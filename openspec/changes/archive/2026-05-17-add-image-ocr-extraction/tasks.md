## 1. Dependencies and configuration

- [x] 1.1 Add `net.sourceforge.tess4j:tess4j` dependency to `pom.xml`
- [x] 1.2 Create `config/OcrProperties.java` with `tessdataPath` and `language` fields bound to `app.ocr.*`
- [x] 1.3 Register `OcrProperties` in `AppConfig` (or via `@EnableConfigurationProperties`)
- [x] 1.4 Add default `app.ocr` block to `application.yml` (and example env var override in README/docker-compose)

## 2. OCR service abstraction

- [x] 2.1 Create `document/processing/ocr/OcrService.java` interface with `String extractText(InputStream image, String contentType)`
- [x] 2.2 Implement `document/processing/ocr/TesseractOcrService.java` using Tess4J, reading config from `OcrProperties`
- [x] 2.3 Map `TesseractException` (and IO errors) to `TextExtractionException` with safe message
- [x] 2.4 Unit test `TesseractOcrService` against a small embedded PNG with known text

## 3. Image text extractor

- [x] 3.1 Create `document/processing/ImageOcrTextExtractor.java` implementing `DocumentTextExtractor`
- [x] 3.2 `supports(contentType)` returns `true` only for `image/png` and `image/jpeg` (case-insensitive)
- [x] 3.3 `extract(InputStream)` delegates to `OcrService` and returns the resulting text
- [x] 3.4 Unit test the extractor with a test PNG and JPEG; assert substring match
- [x] 3.5 Unit test that `supports` rejects other content types

## 4. Pipeline integration

- [x] 4.1 Confirm `TextExtractorRegistry` auto-discovers the new bean (no changes expected; verify with a smoke test)
- [x] 4.2 Remove the legacy "OCR not implemented yet" handling in `DocumentProcessingService` (and any conditional that special-cases image content types)
- [x] 4.3 Update existing tests in `DocumentProcessingService` that assert the old `FAILED` + `"OCR not implemented yet"` behavior for images

## 5. End-to-end verification

- [x] 5.1 Add integration test: upload a PNG with known text → poll status until `PROCESSED` → assert `document_result.raw_text` contains expected substring
- [x] 5.2 Add integration test: when `OcrService` throws, status transitions to `FAILED` with safe `errorMessage` and no `document_result` row exists
- [x] 5.3 Run full test suite (`./mvnw test`) and fix regressions
- [x] 5.4 Manual smoke test via Postman collection: upload a real PNG and inspect the resulting document and result rows

## 6. Documentation

- [x] 6.1 Update README with `tessdata` setup instructions (local path or volume mount) and the new `app.ocr.*` properties
- [x] 6.2 Note OCR support in `document-intelligence-pipeline-planning.md` Fase 4 section (mark Fase 4 done once merged)
