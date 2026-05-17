## Why

Hoy `DocumentUploadService` publica `DocumentUploadedEvent` con `ApplicationEventPublisher` (in-process). El planning Fase 6 pide convertir eso en un evento Kafka real para empezar a practicar el flujo event-driven: producer, JSON envelope, correlation IDs. Esto es la base para Fase 7 (consumer/worker separado) y para integrar otros suscriptores aguas abajo (auditoría, notificaciones).

## What Changes

- Agregar `spring-kafka` al `pom.xml`.
- Nuevo topic `document.uploaded` (configurable vía `app.kafka.topics.document-uploaded`).
- Nuevo evento de transporte `DocumentUploadedKafkaEvent` (record) con envelope JSON: `eventId` (UUID), `type` ("DocumentUploaded"), `documentId`, `occurredAt` (Instant ISO), `correlationId`.
- Nueva interface `DocumentEventPublisher` + implementación `KafkaDocumentEventPublisher`. El `DocumentUploadService` inyecta la interface y publica después del `documentRepository.save(...)`.
- Aceptar header `X-Correlation-Id` en `POST /documents`; si no viene, generar uno. Loguear con MDC.
- **El `ApplicationEventPublisher` y `DocumentProcessingListener` quedan intactos** — la Fase 7 los reemplazará por un consumer Kafka real. Esta change sólo agrega un canal adicional de salida.
- Si Kafka está caído cuando se intenta publicar: loguear ERROR, NO romper la respuesta HTTP (el upload ya quedó persistido y el processing interno corre igual). Outbox queda como follow-up.
- docker-compose: agregar `kafka` (Bitnami) y `kafka-ui` opcional.
- Tests: unit del publisher + integration con `KafkaContainer` (Testcontainers) consumiendo del topic.

## Capabilities

### New Capabilities
<!-- ninguna -->

### Modified Capabilities
- `document-upload`: nuevo requirement para publicar el evento Kafka tras un upload exitoso, propagación de `correlationId`, y comportamiento ante fallas del broker.

## Impact

- **Código**: nueva interface + impl Kafka, modificaciones en `DocumentController` (header), `DocumentUploadService` (publicar vía interface), nuevo `KafkaProperties`. La clase `DocumentUploadedEvent` interna se mantiene; convive con el nuevo `DocumentUploadedKafkaEvent`.
- **Dependencias**: `org.springframework.kafka:spring-kafka` (compile), `org.testcontainers:kafka` (test).
- **Infra local**: `docker-compose.yml` agrega un broker Kafka. Variables `app.kafka.bootstrap-servers` y `app.kafka.topics.document-uploaded` en `application.yml`.
- **Schema DB**: ninguno.
- **API HTTP**: backward-compatible. El header `X-Correlation-Id` es opcional. Response shape no cambia.
- **Tests**: nueva clase de integration test específica para Kafka + `KafkaContainer`. Los demás integration tests del upload siguen pasando.
- **Procesamiento existente**: NO cambia. El listener in-process sigue siendo la ruta real hacia `DocumentProcessingService`.
