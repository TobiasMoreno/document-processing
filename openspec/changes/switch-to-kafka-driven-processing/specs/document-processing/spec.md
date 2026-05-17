## MODIFIED Requirements

### Requirement: Successful upload triggers asynchronous processing
After a successful `POST /documents` (response `201 Created` with `status: UPLOADED`), the system SHALL trigger document processing via a Kafka consumer subscribed to the `document.uploaded` topic. The HTTP response SHALL NOT block on processing. Processing SHALL occur on the Kafka listener container thread (or pool thread when consumer concurrency > 1). The in-process `ApplicationEventPublisher` path SHALL NOT be used to trigger processing.

#### Scenario: Upload returns immediately while processing runs in background
- **WHEN** a client uploads a valid document via `POST /documents`
- **THEN** the response is returned with `status: UPLOADED` before any text extraction begins
- **AND** processing of that document is subsequently triggered by the Kafka consumer reading the published `DocumentUploaded` event

#### Scenario: Processing is driven by Kafka, not in-process events
- **WHEN** a `DocumentUploaded` envelope arrives on the `document.uploaded` topic for a document in `UPLOADED` state
- **THEN** the Kafka consumer invokes the processing service for that `documentId`
- **AND** the document transitions through `PROCESSING` to `PROCESSED` or `FAILED`

#### Scenario: Multiple uploads are processed independently
- **WHEN** multiple valid documents are uploaded in quick succession
- **THEN** each one is processed independently as their corresponding Kafka events are consumed

## ADDED Requirements

### Requirement: Kafka redelivery does not cause double processing
The Kafka consumer SHALL be safe against at-least-once delivery semantics. If the same `DocumentUploaded` event is delivered more than once for the same `documentId`, the system SHALL only execute the extraction work once. Subsequent deliveries SHALL be acknowledged (offset committed) without re-running extraction and without altering the document's state.

#### Scenario: Re-delivered event after PROCESSED is acknowledged and ignored
- **WHEN** a `DocumentUploaded` event is delivered for a document whose status is already `PROCESSED`
- **THEN** no new extraction is performed
- **AND** the document remains in `PROCESSED`
- **AND** the consumer commits the offset (does not enter a redelivery loop)

#### Scenario: Re-delivered event after FAILED is acknowledged and ignored
- **WHEN** a `DocumentUploaded` event is delivered for a document whose status is already `FAILED`
- **THEN** no new extraction is performed
- **AND** the document remains in `FAILED`
- **AND** the consumer commits the offset

### Requirement: Correlation ID from envelope is propagated to processing logs
When the Kafka consumer handles a `DocumentUploaded` event, the `correlationId` from the envelope SHALL be placed into the SLF4J MDC under key `correlationId` for the duration of the handler. Logs emitted by `DocumentProcessingService` and downstream collaborators during that invocation SHALL therefore include the same `correlationId` that was attached to the originating HTTP request.

#### Scenario: Correlation id flows from upload through processing logs
- **WHEN** a client uploads a document with header `X-Correlation-Id: corr-xyz`
- **AND** the Kafka consumer subsequently processes the resulting event
- **THEN** log statements emitted during that processing carry `correlationId=corr-xyz` in MDC
