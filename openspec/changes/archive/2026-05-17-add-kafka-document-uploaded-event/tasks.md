## 1. Dependencies and configuration

- [x] 1.1 Add `org.springframework.kafka:spring-kafka` to `pom.xml` (managed version from Boot)
- [x] 1.2 Add `org.testcontainers:kafka` to `pom.xml` (test scope)
- [x] 1.3 Create `config/KafkaProperties.java` with `bootstrapServers` and `Topics topics` (nested record with `documentUploaded`) bound to `app.kafka.*`
- [x] 1.4 Register `KafkaProperties` in `AppConfig` via `@EnableConfigurationProperties`
- [x] 1.5 Add default `app.kafka` block to `application.yml` (`bootstrap-servers: localhost:9092`, `topics.document-uploaded: document.uploaded`)
- [x] 1.6 Create `config/KafkaConfig.java`: producer factory (acks=all, idempotence=true, linger.ms=10, JSON value serializer), `KafkaTemplate<String, DocumentUploadedKafkaEvent>` bean, `KafkaAdmin` + `NewTopic` bean for `document.uploaded` (3 partitions, RF 1)

## 2. Event envelope and publisher

- [x] 2.1 Create `document/event/DocumentUploadedKafkaEvent.java` record with `eventId` (UUID), `type` (String, defaulted to "DocumentUploaded" via a static factory), `documentId` (UUID), `occurredAt` (Instant), `correlationId` (String). Apply `@JsonInclude(NON_NULL)`.
- [x] 2.2 Create `document/event/DocumentEventPublisher.java` interface with `void publishUploaded(DocumentUploadedKafkaEvent event)`
- [x] 2.3 Implement `document/event/KafkaDocumentEventPublisher.java` (`@Component`) using `KafkaTemplate`. Send with key = `documentId.toString()`, value = event. Block on the resulting `CompletableFuture` with a configurable short timeout (default 5s) to surface broker errors synchronously; catch all exceptions and log ERROR with `documentId` + `correlationId`. NEVER rethrow — return normally.

## 3. Correlation ID handling

- [x] 3.1 Create `web/CorrelationIdFilter.java` (`OncePerRequestFilter`) that reads `X-Correlation-Id` header, falls back to `UUID.randomUUID().toString()` when missing/blank, places the value into MDC under key `correlationId`, exposes it on the response under the same header, and clears MDC in `finally`
- [x] 3.2 Register the filter as a bean (Spring picks it up automatically as `@Component`)
- [x] 3.3 Truncate any input `X-Correlation-Id` longer than 64 chars to 64 (defensive)

## 4. Wire into upload flow

- [x] 4.1 Inject `DocumentEventPublisher` into `DocumentUploadService`
- [x] 4.2 After `documentRepository.save(...)` succeeds, build a `DocumentUploadedKafkaEvent` (read `correlationId` from MDC, fallback to a generated UUID when MDC is empty — defensive for non-HTTP callers) and call `eventPublisher.publishUploaded(...)`
- [x] 4.3 Keep the existing `ApplicationEventPublisher.publishEvent(new DocumentUploadedEvent(...))` call — the in-process listener stays intact for this phase

## 5. Unit tests

- [x] 5.1 `DocumentUploadedKafkaEventTest`: serialize via the same `ObjectMapper` config the producer uses; assert JSON shape matches the envelope (`eventId`, `type`, `documentId`, `occurredAt` ISO, `correlationId`)
- [x] 5.2 `KafkaDocumentEventPublisherTest`: mock `KafkaTemplate`, assert send is called with the configured topic + correct key + value; simulate `send().get()` throwing and assert the method returns normally (no exception escapes)
- [x] 5.3 `CorrelationIdFilterTest`: with header present → MDC + response header carry the value; without header → both get a generated UUID; overlong header → truncated to 64 chars
- [x] 5.4 Update `DocumentUploadServiceTest` to mock `DocumentEventPublisher` and assert it is called once with an event whose `documentId` matches the persisted document

## 6. Integration tests

- [x] 6.1 Extend `IntegrationTestBase` with a `KafkaContainer` (Bitnami or Confluent image), register `spring.kafka.bootstrap-servers` + `app.kafka.bootstrap-servers` via `@DynamicPropertySource`
- [x] 6.2 New `DocumentUploadKafkaIntegrationTest extends IntegrationTestBase`: upload a valid PDF; subscribe a Kafka consumer to `document.uploaded`; await one record; assert key = documentId, value JSON has all 5 envelope fields with expected values
- [x] 6.3 Same class: send `X-Correlation-Id: abc-123` header; assert the resulting event's `correlationId` equals `abc-123`
- [x] 6.4 Same class: assert two consecutive uploads produce two events with distinct `eventId`/`documentId`
- [~] 6.5 Skipped: covered at unit level by `KafkaDocumentEventPublisherTest.swallowsExceptionsToKeepUploadFlowAlive`. Adding an integration-level duplicate would not exercise additional code paths, since the publisher's swallowing behavior is what the integration test would rely on.

## 7. Infrastructure and docs

- [x] 7.1 Add `kafka` service to `docker-compose.yml` (Bitnami KRaft image, single broker, expose 9092)
- [x] 7.2 Add `kafka-ui` service (optional, expose 8080) for manual inspection
- [~] 7.3 Skipped: no `README.md` exists in the repo. Local-setup docs live in `docker-compose.yml` header comments and `document-intelligence-pipeline-planning.md`. Both updated.
- [x] 7.4 Update `document-intelligence-pipeline-planning.md` Fase 6 with a checkmark + summary of the envelope and broker setup

## 8. Verification

- [x] 8.1 Run `./mvnw test`; ensure 100% pass (no skipped tests other than the preexisting Tesseract live test)
- [x] 8.2 Manual smoke: start docker-compose, upload `02-2026.pdf`, tail Kafka UI (or `kafka-console-consumer`) and confirm one envelope arrives with the expected fields
