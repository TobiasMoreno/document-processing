## 1. Consumer configuration

- [x] 1.1 Extend `KafkaProperties` with `Consumer consumer` (nested), fields `groupId` (default `document-processor`) and `concurrency` (int, default 1)
- [x] 1.2 Add `app.kafka.consumer.group-id` and `app.kafka.consumer.concurrency` to `application.yml`
- [x] 1.3 In `KafkaConfig`: declare `ConsumerFactory<String, DocumentUploadedKafkaEvent>` using `JsonDeserializer<>(DocumentUploadedKafkaEvent.class, objectMapper, false)` (no type headers); `bootstrap.servers`, `group.id`, `auto.offset.reset=earliest`, `enable.auto.commit=false`
- [x] 1.4 In `KafkaConfig`: declare `ConcurrentKafkaListenerContainerFactory<String, DocumentUploadedKafkaEvent>` bean; set concurrency from properties; default ack mode (RECORD)

## 2. Consumer implementation

- [x] 2.1 Create `document/processing/DocumentUploadedKafkaConsumer.java` (`@Component`) with `@KafkaListener(topics = "${app.kafka.topics.document-uploaded}", groupId = "${app.kafka.consumer.group-id}", containerFactory = "documentEventListenerContainerFactory")`
- [x] 2.2 Handler signature: `void onMessage(DocumentUploadedKafkaEvent event)`. Place `event.correlationId()` into MDC (key `correlationId`, fallback `"unknown"` if null), invoke `documentProcessingService.process(event.documentId())`, clear MDC in `finally`
- [x] 2.3 If `event` is null or `documentId()` is null, log warn and return (acknowledge anyway)

## 3. Remove in-process trigger path

- [x] 3.1 Delete `document/processing/DocumentProcessingListener.java`
- [x] 3.2 Delete `document/event/DocumentUploadedEvent.java`
- [x] 3.3 In `DocumentUploadService`: remove `ApplicationEventPublisher` field, constructor param, and the `publishEvent(...)` call; remove the corresponding import
- [x] 3.4 Grep the codebase for `@Async` and `documentProcessingExecutor` usages. If `DocumentProcessingListener` was the only consumer, delete `config/AsyncConfig.java` + `config/AsyncProperties.java` and remove their registration in `AppConfig`/`application.yml`. Otherwise leave them.

## 4. Tests: unit

- [x] 4.1 Update `DocumentUploadServiceTest`: drop the `ApplicationEventPublisher` mock + constructor arg. Drop any assertion that referenced `publishEvent`. Keep the existing `kafkaEventPublisher` assertion (Fase 6).
- [x] 4.2 Create `DocumentUploadedKafkaConsumerTest`: mock `DocumentProcessingService`; build an event with a known `correlationId` and `documentId`; invoke `onMessage` directly; capture MDC during the call (use a spy on the service that reads MDC); assert `process(documentId)` is called once; assert MDC is cleared afterwards
- [x] 4.3 In the consumer test, also cover the null-event / null-documentId paths: ensure no NPE escapes and `process` is not called

## 5. Tests: integration

- [x] 5.1 Verify `DocumentProcessingIntegrationTest` (existing) still passes — it now exercises the full Kafka roundtrip implicitly (upload publishes → consumer reads → process). Raise the Awaitility timeout from 15s to 25s if needed for Kafka roundtrip stability.
- [x] 5.2 Create `DocumentUploadedKafkaConsumerIntegrationTest extends IntegrationTestBase`: persist a Document row in `UPLOADED` state with bytes already in MinIO (mimic the post-upload state); produce a hand-crafted envelope to `document.uploaded` directly via a `KafkaTemplate`; await the document reaches `PROCESSED`. This validates the consumer in isolation from the upload flow.
- [x] 5.3 In the same class: produce TWO envelopes with the same `documentId` (already PROCESSED after the first), assert the second delivery is acknowledged without side effects (status remains `PROCESSED`, `processedAt` does not change)

## 6. Verification and cleanup

- [x] 6.1 Run `./mvnw test`; ensure all green (except the preexisting Tesseract live skip)
- [x] 6.2 Confirm no orphan imports of `DocumentUploadedEvent` or `DocumentProcessingListener` remain (compile error would catch this, but double-check)
- [x] 6.3 Update `document-intelligence-pipeline-planning.md` Fase 7 with ✅ + summary (consumer groupId, ack mode, idempotency strategy, known limitation about docs stuck in `UPLOADED` if broker is down during publish)
- [ ] 6.4 Manual smoke: docker-compose up; upload via Postman; observe consumer log line; observe doc reaches `PROCESSED`; restart only the Kafka container; verify the doc stays `UPLOADED` (documented limitation)
