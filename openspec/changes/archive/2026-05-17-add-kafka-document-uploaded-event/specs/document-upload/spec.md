## ADDED Requirements

### Requirement: Upload publishes a DocumentUploaded event to Kafka
After a successful upload (object stored + metadata row persisted), the system SHALL publish a `DocumentUploaded` event to the Kafka topic `document.uploaded` (configurable). The message value SHALL be a JSON object with fields: `eventId` (UUID), `type` (literal `"DocumentUploaded"`), `documentId` (UUID, matching the response body), `occurredAt` (ISO-8601 instant), and `correlationId` (string). The message key SHALL be the `documentId` as string.

#### Scenario: Upload publishes event with expected envelope
- **WHEN** a valid file is uploaded via `POST /documents`
- **THEN** a single message is produced to the configured `document.uploaded` topic
- **AND** the message key equals the returned `documentId`
- **AND** the message value is a JSON object containing `eventId` (a UUID), `type = "DocumentUploaded"`, `documentId` matching the response, `occurredAt` parseable as an ISO instant, and `correlationId` non-empty

#### Scenario: Two uploads produce two distinct events
- **WHEN** two valid uploads complete in succession
- **THEN** two messages are produced to `document.uploaded`
- **AND** their `eventId` values differ
- **AND** their `documentId` values differ and match the respective response bodies

### Requirement: Correlation ID is accepted from the request and propagated to the event
The system SHALL read the optional `X-Correlation-Id` HTTP header on `POST /documents`. If present and non-blank, the value SHALL be used as the `correlationId` of the published Kafka event and SHALL be placed into the SLF4J MDC under the key `correlationId` for the duration of the request. If absent or blank, the system SHALL generate a fresh UUID string and use it as the `correlationId`.

#### Scenario: Caller-supplied correlation id is propagated
- **WHEN** a client uploads with header `X-Correlation-Id: abc-123`
- **THEN** the Kafka event's `correlationId` field equals `abc-123`

#### Scenario: Missing correlation id is auto-generated
- **WHEN** a client uploads without the `X-Correlation-Id` header
- **THEN** the Kafka event's `correlationId` field is a non-blank string

### Requirement: Upload remains successful when Kafka publish fails
If publishing the `DocumentUploaded` event to Kafka fails (broker unavailable, send timeout, serialization error), the system SHALL NOT undo the upload and SHALL still respond `201 Created` with the persisted `documentId`. The failure SHALL be logged at ERROR level including the `documentId` and `correlationId`. No new error response code is introduced for this case.

#### Scenario: Broker unavailable does not break upload
- **WHEN** the configured Kafka broker is unreachable at the moment of publish
- **AND** a client uploads a valid file
- **THEN** the response is `201 Created` with `status: UPLOADED` and a fresh `documentId`
- **AND** the document row and the stored object both exist
- **AND** an ERROR log entry is recorded referencing the `documentId`
