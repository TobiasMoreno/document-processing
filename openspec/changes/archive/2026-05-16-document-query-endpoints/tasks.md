## 1. Domain / service layer

- [x] 1.1 Create `DocumentNotFoundException` (extends `RuntimeException`) in package `document_processing.tobias_moreno.document`
- [x] 1.2 Create `DocumentQueryService` with `findById(UUID id)` returning `Document`, throwing `DocumentNotFoundException` when absent
- [x] 1.3 (Optional) Add `findStatus(UUID id)` returning just the status if a projection is desired — otherwise reuse `findById`

## 2. DTOs

- [x] 2.1 Create `DocumentResponse` record with `documentId`, `originalFilename`, `contentType`, `sizeBytes`, `status`, `createdAt`, `updatedAt`, `processedAt`, `errorMessage`
- [x] 2.2 Add a static factory `DocumentResponse.from(Document)` that does the field mapping (no `storagePath`)
- [x] 2.3 Create `DocumentStatusResponse` record with `documentId` and `status` plus a `from(Document)` factory

## 3. HTTP layer

- [x] 3.1 Add `GET /documents/{id}` to `DocumentController` returning `200` with `DocumentResponse`
- [x] 3.2 Add `GET /documents/{id}/status` to `DocumentController` returning `200` with `DocumentStatusResponse`
- [x] 3.3 Declare `@PathVariable UUID id` in both handlers so Spring binds and validates the UUID format

## 4. Error handling

- [x] 4.1 Add a `@ExceptionHandler(DocumentNotFoundException.class)` to `GlobalExceptionHandler` → `404` with `{ error: "DOCUMENT_NOT_FOUND", message }`
- [x] 4.2 Add a `@ExceptionHandler(MethodArgumentTypeMismatchException.class)` that responds `400 INVALID_DOCUMENT_ID` when the required type is `UUID`; for other types delegate to the generic handler (or return a generic 400)
- [x] 4.3 Verify all responses keep the existing `{ error, message }` shape and do not leak internals

## 5. Tests — unit

- [x] 5.1 Unit test `DocumentQueryService.findById`: returns entity when present
- [x] 5.2 Unit test `DocumentQueryService.findById`: throws `DocumentNotFoundException` when repository returns empty
- [x] 5.3 Unit test `DocumentResponse.from(...)`: maps fields and does NOT include `storagePath` (reflective check that the record has no `storagePath` component, or a JSON serialization check)

## 6. Tests — integration

- [x] 6.1 Integration test `GET /documents/{id}` happy path: upload a PDF via `POST /documents`, then fetch and assert the response body matches the uploaded file (filename, contentType, sizeBytes, status=UPLOADED, no `storagePath` field)
- [x] 6.2 Integration test `GET /documents/{id}/status` happy path: returns `{ documentId, status }` only
- [x] 6.3 Integration test 404 on `GET /documents/{id}` for a random non-existing UUID, asserting body `{ error: "DOCUMENT_NOT_FOUND", message }`
- [x] 6.4 Integration test 404 on `GET /documents/{id}/status` for a random non-existing UUID
- [x] 6.5 Integration test 400 on `GET /documents/not-a-uuid` asserting `error: INVALID_DOCUMENT_ID`
- [x] 6.6 Integration test 400 on `GET /documents/abc/status` asserting `error: INVALID_DOCUMENT_ID`
- [x] 6.7 Integration test idempotency: two consecutive GETs on the same id return identical bodies

## 7. Postman

- [x] 7.1 Add `GET /documents/{{lastDocumentId}}` request to `postman/document-processing.postman_collection.json` (uses the collection variable set by the upload happy path)
- [x] 7.2 Add `GET /documents/{{lastDocumentId}}/status` request
- [x] 7.3 Add a 404 example request hardcoding a random UUID
- [x] 7.4 Add a 400 example request with a non-UUID id

## 8. Verification

- [x] 8.1 `./mvnw test` passes the full suite green (including previous tests)
- [x] 8.2 `docker compose up -d` + `./mvnw spring-boot:run`, upload one PDF, then verify both GETs by hand (curl or Postman)
- [x] 8.3 `openspec validate document-query-endpoints --strict` succeeds
