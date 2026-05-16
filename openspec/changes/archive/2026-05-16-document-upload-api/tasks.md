## 1. Build setup and dependencies

- [x] 1.1 Confirm Java 21 toolchain in the build file (Gradle/Maven) and align Spring Boot starter version
- [x] 1.2 Add dependencies: `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-data-jpa`, `postgresql` driver, `flyway-core`, `flyway-database-postgresql`, `io.minio:minio`, `org.apache.tika:tika-core`
- [x] 1.3 Add test dependencies: `spring-boot-starter-test`, `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql`, `org.testcontainers:minio`
- [x] 1.4 Configure `spring.servlet.multipart.max-file-size=10MB` and `max-request-size=12MB` in `application.yml`
- [x] 1.5 Set `spring.jpa.hibernate.ddl-auto=validate` and Flyway defaults in `application.yml`

## 2. Local environment

- [x] 2.1 Create `docker-compose.yml` with `postgres:16-alpine` service (named volume, healthcheck, exposed 5432)
- [x] 2.2 Add `minio/minio:latest` service to compose (named volume, healthcheck, ports 9000/9001, root user/password)
- [x] 2.3 Add one-shot `minio-init` service using `minio/mc` that creates the configured bucket if missing, depending on minio healthy
- [x] 2.4 Add a short README section (or comment in compose) explaining `docker-compose up -d` and the default credentials

## 3. Database schema

- [x] 3.1 Create Flyway migration `V1__create_document_table.sql` with the `document` table per design (UUID PK, status varchar, timestamps, indexes on `status` and `created_at`)
- [x] 3.2 Verify migration applies cleanly against an empty Postgres (manual run via `docker-compose up`)

## 4. Domain layer

- [x] 4.1 Create `Document` JPA entity with fields `id`, `originalFilename`, `contentType`, `sizeBytes`, `storagePath`, `status`, `errorMessage`, `createdAt`, `updatedAt`, `processedAt`
- [x] 4.2 Create `DocumentStatus` enum with `UPLOADED`, `PROCESSING`, `PROCESSED`, `FAILED` (stored as string)
- [x] 4.3 Create `DocumentRepository extends JpaRepository<Document, UUID>`
- [x] 4.4 Add `@PrePersist`/`@PreUpdate` (or auditing) to maintain `createdAt`/`updatedAt`

## 5. Storage abstraction

- [x] 5.1 Define `ObjectStorage` interface with `store(String key, InputStream in, long size, String contentType)` and `delete(String key)`
- [x] 5.2 Implement `MinioObjectStorage` using the official MinIO client; configure bean wiring from `app.storage.minio.*` properties
- [x] 5.3 Implement startup bucket check: ensure configured bucket exists or fail fast with a clear message
- [x] 5.4 Make `delete` idempotent (swallow "object not found" responses)
- [x] 5.5 Implement an `ObjectKeyGenerator` producing `{yyyy}/{MM}/{dd}/{documentId}` (UTC) and unit-test it

## 6. Upload service

- [x] 6.1 Create `DocumentUploadService.upload(MultipartFile file)` that returns the persisted `Document`
- [x] 6.2 Validate non-empty file → throw `EmptyFileException`
- [x] 6.3 Validate size ≤ 10 MB → throw `FileTooLargeException`
- [x] 6.4 Sniff content-type with Tika; reject if not in allowed set or if it disagrees with the declared multipart `Content-Type` → throw `InvalidContentTypeException`
- [x] 6.5 Generate `UUID` and compute storage key; upload to MinIO via `ObjectStorage.store`
- [x] 6.6 Persist `Document` row with `status = UPLOADED`
- [x] 6.7 On DB failure after a successful upload, invoke `ObjectStorage.delete` (best-effort, log on failure) and rethrow

## 7. HTTP layer

- [x] 7.1 Create `DocumentController` with `POST /documents` accepting `@RequestParam("file") MultipartFile`
- [x] 7.2 Return `201 Created` with body `{ documentId, status }` (use a `UploadResponse` DTO)
- [x] 7.3 Create `@RestControllerAdvice` mapping `EmptyFileException` → `400 EMPTY_FILE`, `FileTooLargeException` → `400 FILE_TOO_LARGE`, `InvalidContentTypeException` → `400 INVALID_CONTENT_TYPE`, MinIO/storage errors → `500 STORAGE_ERROR`, generic → `500 INTERNAL_ERROR`
- [x] 7.4 Ensure error responses contain only `{ error, message }` and never include stack traces or internal paths

## 8. Configuration

- [x] 8.1 Define `@ConfigurationProperties("app.storage.minio")` class with `endpoint`, `accessKey`, `secretKey`, `bucket`
- [x] 8.2 Define `@ConfigurationProperties("app.upload")` with `maxFileSize` (bytes) and `allowedContentTypes` (list)
- [x] 8.3 Provide `application.yml` defaults matching `docker-compose.yml`
- [x] 8.4 Provide `application-test.yml` placeholders for Testcontainers-driven overrides

## 9. Tests

- [x] 9.1 Unit test `ObjectKeyGenerator` (date partitioning, UUID embedding)
- [x] 9.2 Unit test `DocumentUploadService` validation paths (mock `ObjectStorage` and repository): empty, too large, unsupported type, mismatched type
- [x] 9.3 Unit test rollback path: when repository save throws, `ObjectStorage.delete` is invoked with the same key
- [x] 9.4 Integration test (Testcontainers Postgres + MinIO) for happy path on PDF: 201 returned, row persisted, object retrievable from bucket
- [x] 9.5 Integration test for `.docx` happy path
- [x] 9.6 Integration test for PNG happy path
- [x] 9.7 Integration test for rejection cases: too large, unsupported type, declared/sniffed mismatch, empty, missing part — assert no row and no object remain
- [x] 9.8 Integration test for error-response shape (no stack traces, only `error` + `message`)

## 10. Verification

- [x] 10.1 `docker-compose up -d` brings up postgres + minio; bucket is created
- [x] 10.2 `./gradlew bootRun` (or `mvn spring-boot:run`) starts the app; Flyway applies `V1`
- [x] 10.3 Manual `curl -F file=@sample.pdf http://localhost:8080/documents` returns `201` with a `documentId`
- [x] 10.4 Verify the object appears in MinIO under `yyyy/MM/dd/{documentId}` and the row exists in `document`
- [x] 10.5 Run full test suite green: `./gradlew test` (or `mvn test`)
- [x] 10.6 Run `openspec validate document-upload-api --strict` and fix any issues
