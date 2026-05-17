## Why

Fase 6 dejó el evento `DocumentUploaded` publicándose a Kafka pero el procesamiento real lo seguía disparando un `@EventListener` in-process. Mientras esos dos caminos convivan, el evento Kafka es decorativo y el sistema no se comporta como event-driven de verdad: no se puede escalar consumidores fuera del proceso, no se pueden agregar retries/DLT con sentido, y las garantías de entrega (at-least-once) no se ejercitan.

Fase 7 cierra el switch: el consumer Kafka pasa a ser el único disparador del procesamiento.

## What Changes

- Nuevo `DocumentUploadedKafkaConsumer` con `@KafkaListener` suscrito a `document.uploaded` (groupId `document-processor`).
- Nueva `ConsumerFactory<String, DocumentUploadedKafkaEvent>` + `ConcurrentKafkaListenerContainerFactory` con `JsonDeserializer` configurado vía type mappings (sin depender de `__TypeId__` headers).
- El consumer propaga `correlationId` del envelope al MDC durante el handler y llama a `DocumentProcessingService.process(documentId)`.
- **BREAKING (interno)**: borrar `DocumentProcessingListener` (`@EventListener @Async`), `DocumentUploadedEvent` (record interno), y la línea `ApplicationEventPublisher.publishEvent(...)` del `DocumentUploadService`.
- Limpiar `AsyncConfig` + `AsyncProperties` si ya no quedan consumidores de `@Async` (lo más probable).
- Nueva property `app.kafka.consumer.group-id` (default `document-processor`) y `app.kafka.consumer.concurrency` (default `1`).
- Idempotencia se apoya en el `markAsProcessing` CAS que ya existe (UPDATE WHERE status=UPLOADED returns 0 rows → skip). No se agrega tabla de eventIds.
- Ack mode: RECORD automático. Excepciones no commitean offset → redelivery.

Sin retry policy ni DLT: queda para Fase 8.

## Capabilities

### New Capabilities
<!-- ninguna -->

### Modified Capabilities
- `document-processing`: el requirement "Successful upload triggers asynchronous processing" se modifica para reflejar que el trigger es ahora un Kafka consumer del topic `document.uploaded`. Se agrega un requirement de idempotencia frente a redelivery de Kafka.

## Impact

- **Código eliminado**: `DocumentProcessingListener`, `DocumentUploadedEvent`, `ApplicationEventPublisher` injection en `DocumentUploadService`. Posiblemente `AsyncConfig` + `AsyncProperties` si no quedan más consumidores.
- **Código nuevo**: `DocumentUploadedKafkaConsumer`, configuración del consumer en `KafkaConfig`.
- **Dependencias**: ninguna nueva (`spring-kafka` ya está).
- **Schema DB**: ninguno.
- **Tests modificados**: `DocumentUploadServiceTest` saca la verificación del `ApplicationEventPublisher`. `DocumentProcessingIntegrationTest` sigue subiendo y polleando estado — pero ahora ejercita el camino Kafka end-to-end (validación implícita del switch).
- **Tests nuevos**: `DocumentUploadedKafkaConsumerTest` (unit con mock service + verificación MDC), un integration test que envía un mensaje directamente al topic (saltándose el upload) y verifica que el doc llega a `PROCESSED`.
- **Comportamiento ante Kafka caído**: si el broker está down cuando llega el upload, el `KafkaDocumentEventPublisher` ya loguea ERROR y devuelve. **El doc queda en `UPLOADED` para siempre** hasta que alguien reprovoque el evento. Documentado como riesgo aceptado en design.
