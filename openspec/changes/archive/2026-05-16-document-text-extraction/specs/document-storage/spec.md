## MODIFIED Requirements

### Requirement: Object storage abstraction
The system SHALL expose an `ObjectStorage` abstraction that hides the concrete storage backend from the rest of the application. The abstraction SHALL provide at minimum: storing a byte stream under a caller-supplied key, reading the stored bytes by key, and deleting an object by key. Callers SHALL NOT depend on any MinIO/S3-specific type.

#### Scenario: Domain code is decoupled from MinIO
- **WHEN** the upload service stores a document
- **THEN** it does so through the `ObjectStorage` abstraction
- **AND** no MinIO SDK type appears in the upload service signature

#### Scenario: Processing service reads through the abstraction
- **WHEN** the processing service needs the bytes of a stored document
- **THEN** it reads them via `ObjectStorage.read(key)` and not directly via the MinIO client in domain code

## ADDED Requirements

### Requirement: Storage supports reading objects by key
The `ObjectStorage` abstraction SHALL expose a `read(String key)` operation that returns the stored bytes as a stream the caller is responsible for closing. Reading a key that does not exist SHALL raise a storage error visible to the caller (no silent empty stream).

#### Scenario: Read returns the previously stored bytes
- **WHEN** a caller stores bytes under key `K` and later invokes `read(K)`
- **THEN** the returned stream yields exactly those bytes

#### Scenario: Read on missing key raises a storage error
- **WHEN** a caller invokes `read` on a key that has never been stored (or was deleted)
- **THEN** a storage error is raised so the caller can react explicitly
