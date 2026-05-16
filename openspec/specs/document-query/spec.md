## Purpose

Expose previously uploaded documents to clients in a read-only, idempotent way: fetch a document's full metadata or only its status by id. Hides internal storage details (paths, backend identifiers) and provides consistent error semantics (404 for unknown ids, 400 for malformed ids) without contacting object storage.

## Requirements

### Requirement: Document metadata can be fetched by id
The system SHALL expose `GET /documents/{id}` returning `200 OK` with a JSON body containing the document's `documentId`, `originalFilename`, `contentType`, `sizeBytes`, `status`, `createdAt`, `updatedAt`, `processedAt`, and `errorMessage`. The response SHALL NOT include the internal storage path or any other internal-only fields.

#### Scenario: Existing document is returned
- **WHEN** a client requests `GET /documents/{id}` for an existing document
- **THEN** the system responds `200 OK` with the document's metadata fields listed above
- **AND** the response body does not contain `storagePath` (or any equivalent internal storage reference)

#### Scenario: Just-uploaded document is queryable immediately
- **WHEN** a client uploads a document via `POST /documents` and then requests `GET /documents/{returnedDocumentId}`
- **THEN** the system responds `200 OK` with `status: UPLOADED`
- **AND** `originalFilename`, `contentType`, and `sizeBytes` match the uploaded file
- **AND** `processedAt` is null and `errorMessage` is null

### Requirement: Document status can be fetched by id
The system SHALL expose `GET /documents/{id}/status` returning `200 OK` with a JSON body containing only `documentId` and `status`.

#### Scenario: Status is returned for an existing document
- **WHEN** a client requests `GET /documents/{id}/status` for an existing document
- **THEN** the system responds `200 OK` with body `{ "documentId": "<uuid>", "status": "<status>" }`
- **AND** the response body contains no other fields

### Requirement: Missing documents return 404 with safe shape
When no document exists for the given id, the system SHALL respond `404 Not Found` with body `{ "error": "DOCUMENT_NOT_FOUND", "message": "<safe message>" }`. The same behavior SHALL apply to both `GET /documents/{id}` and `GET /documents/{id}/status`.

#### Scenario: Metadata endpoint returns 404 for unknown id
- **WHEN** a client requests `GET /documents/{id}` for an id with no matching row
- **THEN** the system responds `404 Not Found`
- **AND** the body equals `{ "error": "DOCUMENT_NOT_FOUND", "message": "<safe message>" }`
- **AND** the message does not contain stack traces or internal paths

#### Scenario: Status endpoint returns 404 for unknown id
- **WHEN** a client requests `GET /documents/{id}/status` for an id with no matching row
- **THEN** the system responds `404 Not Found` with `error: DOCUMENT_NOT_FOUND`

### Requirement: Malformed document ids return 400
When the `{id}` path segment is not a valid UUID, the system SHALL respond `400 Bad Request` with body `{ "error": "INVALID_DOCUMENT_ID", "message": "<safe message>" }`. This SHALL apply to both query endpoints.

#### Scenario: Non-UUID id on metadata endpoint
- **WHEN** a client requests `GET /documents/not-a-uuid`
- **THEN** the system responds `400 Bad Request` with `error: INVALID_DOCUMENT_ID`
- **AND** no database lookup is performed

#### Scenario: Non-UUID id on status endpoint
- **WHEN** a client requests `GET /documents/abc/status`
- **THEN** the system responds `400 Bad Request` with `error: INVALID_DOCUMENT_ID`

### Requirement: Query endpoints are read-only
Query endpoints SHALL NOT modify any document state, SHALL NOT read from object storage, and SHALL be safe to call repeatedly. Successive identical queries SHALL return identical responses (modulo `updatedAt` changes driven by external updates).

#### Scenario: Repeated queries return the same data
- **WHEN** a client requests `GET /documents/{id}` twice in a row for an unchanged document
- **THEN** both responses contain the same body

#### Scenario: Query does not contact object storage
- **WHEN** the configured object storage backend is unreachable but Postgres is reachable
- **THEN** `GET /documents/{id}` and `GET /documents/{id}/status` continue to respond successfully for existing documents
