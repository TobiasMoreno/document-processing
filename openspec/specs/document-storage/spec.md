## Purpose

Abstract the persistence of document bytes behind a small, backend-agnostic interface so the rest of the system can store and reference files without depending on the concrete object-storage client. Initial implementation uses MinIO (S3-compatible); the abstraction keeps the door open for S3 or other backends without touching the domain.

## Requirements

### Requirement: Object storage abstraction
The system SHALL expose an `ObjectStorage` abstraction that hides the concrete storage backend from the rest of the application. The abstraction SHALL provide at minimum: storing a byte stream under a caller-supplied key, reading the stored bytes by key, and deleting an object by key. Callers SHALL NOT depend on any MinIO/S3-specific type.

#### Scenario: Domain code is decoupled from MinIO
- **WHEN** the upload service stores a document
- **THEN** it does so through the `ObjectStorage` abstraction
- **AND** no MinIO SDK type appears in the upload service signature

#### Scenario: Processing service reads through the abstraction
- **WHEN** the processing service needs the bytes of a stored document
- **THEN** it reads them via `ObjectStorage.read(key)` and not directly via the MinIO client in domain code

### Requirement: MinIO-backed implementation
The system SHALL provide a MinIO-backed implementation of `ObjectStorage` that connects to a configurable endpoint, access key, secret key, and bucket. Configuration SHALL be supplied via Spring properties under `app.storage.minio.*`.

#### Scenario: Configuration is loaded from properties
- **WHEN** the application starts with `app.storage.minio.endpoint`, `access-key`, `secret-key`, and `bucket` configured
- **THEN** the MinIO client connects to the configured endpoint using those credentials
- **AND** subsequent stores write objects into the configured bucket

#### Scenario: Bucket is ensured at startup
- **WHEN** the application starts and the configured bucket does not yet exist
- **THEN** the application either creates the bucket or fails fast with a clear error message identifying the missing bucket

### Requirement: Object keys are deterministic and date-partitioned
Object keys SHALL follow the pattern `{yyyy}/{MM}/{dd}/{documentId}` where the date is the upload date in UTC and `documentId` is the UUID assigned to the document. Keys SHALL NOT include the original filename or extension.

#### Scenario: Key uses upload date partition
- **WHEN** a document with id `11111111-2222-3333-4444-555555555555` is uploaded on `2026-05-16` UTC
- **THEN** the resulting object key is `2026/05/16/11111111-2222-3333-4444-555555555555`

### Requirement: Stored objects retain content-type metadata
When storing an object the system SHALL set the object's content-type metadata to the effective (sniffed) content-type of the uploaded file.

#### Scenario: Content-type metadata is preserved
- **WHEN** a PDF is stored
- **THEN** the resulting MinIO object has `Content-Type: application/pdf` in its metadata

### Requirement: Storage supports deletion for rollback
The `ObjectStorage` abstraction SHALL support deleting an object by key so the upload flow can roll back a stored object when the subsequent database insert fails. Deletion SHALL be idempotent: deleting a non-existent key SHALL NOT raise an error to the caller.

#### Scenario: Delete after failed insert
- **WHEN** an object has been stored and the caller invokes delete on its key
- **THEN** the object is removed from the bucket

#### Scenario: Delete is idempotent
- **WHEN** delete is called with a key that does not exist
- **THEN** no exception is propagated to the caller

### Requirement: Storage supports reading objects by key
The `ObjectStorage` abstraction SHALL expose a `read(String key)` operation that returns the stored bytes as a stream the caller is responsible for closing. Reading a key that does not exist SHALL raise a storage error visible to the caller (no silent empty stream).

#### Scenario: Read returns the previously stored bytes
- **WHEN** a caller stores bytes under key `K` and later invokes `read(K)`
- **THEN** the returned stream yields exactly those bytes

#### Scenario: Read on missing key raises a storage error
- **WHEN** a caller invokes `read` on a key that has never been stored (or was deleted)
- **THEN** a storage error is raised so the caller can react explicitly
