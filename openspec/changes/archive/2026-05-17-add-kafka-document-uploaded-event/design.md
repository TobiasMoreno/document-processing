## Context

`DocumentUploadService.upload(...)` actualmente termina con `eventPublisher.publishEvent(new DocumentUploadedEvent(persisted.getId()))` usando `ApplicationEventPublisher` de Spring. Ese evento es consumido por `DocumentProcessingListener` (in-process, `@EventListener` async) que dispara el procesamiento.

El planning Fase 6 pide convertir esa señal interna en un evento Kafka externo. Pero también pide hacerlo de forma incremental — Fase 6 publica, Fase 7 consume. Por eso esta change agrega un canal de salida adicional sin romper el flujo interno que ya funciona.

## Goals / Non-Goals

**Goals:**
- Publicar `DocumentUploadedKafkaEvent` al topic `document.uploaded` después de cada upload exitoso.
- Definir un envelope JSON estable que los suscriptores futuros (worker en Fase 7, audit, etc.) puedan deserializar.
- Propagar `correlationId` desde HTTP → MDC → evento Kafka.
- Mantener el upload tolerante a fallas del broker: si Kafka está caído, el cliente recibe 201 y el documento queda persistido. El processing interno NO depende de Kafka todavía.
- Setup local de Kafka en docker-compose y para tests con Testcontainers.

**Non-Goals:**
- NO consumer Kafka en esta fase. Sigue corriendo el listener in-process. Fase 7 hará el switch.
- NO outbox pattern. Si Kafka falla, se pierde el mensaje (el doc queda en BD igual). Outbox queda como follow-up de Fase 8.
- NO schema registry, Avro o Protobuf. JSON crudo con Jackson.
- NO `DocumentProcessed` ni `DocumentProcessingFailed` todavía — son Fase 7/8.
- NO Kafka transactions. Acks=all es suficiente para esta fase.
- NO autenticación SASL/SSL contra el broker. Local development plaintext.

## Decisions

### 1. Dos tipos de evento, uno por canal
- `DocumentUploadedEvent` (existente) — transporte intra-JVM, sigue siendo el que dispara el processing.
- `DocumentUploadedKafkaEvent` (nuevo, record) — contrato externo JSON.

**Por qué separados:** acoplarlos lleva a que cualquier cambio del esquema Kafka (rename, agregar fields opcionales) impacte código interno. Manteniéndolos separados, el contrato externo evoluciona con su propia versionable shape.

**Alternativa considerada:** un solo record con campos opcionales para "uso interno vs externo". Rechazada — viola separación de responsabilidades.

### 2. `DocumentEventPublisher` interface
```java
public interface DocumentEventPublisher {
    void publishUploaded(DocumentUploadedKafkaEvent event);
}
```
Impl `KafkaDocumentEventPublisher` usa `KafkaTemplate<String, DocumentUploadedKafkaEvent>` con key = `documentId.toString()` (afinidad por documento si futuro escalamos a múltiples particiones).

**Por qué interface:** facilita stubs en tests unitarios sin levantar Kafka. Permite hipotético `LoggingDocumentEventPublisher` para perfiles dev sin broker.

### 3. Publicación post-save, sin transactional outbox
La publicación ocurre **inmediatamente después** de `documentRepository.save(...)`. No usamos `@TransactionalEventListener(AFTER_COMMIT)` porque el método `upload` no es transaccional explícito (cada repo call es su propio commit por el default behavior de Spring Data). El save ya está commiteado cuando llega la línea siguiente.

Trade-off conocido: si la JVM muere entre el `save` y el `publish`, se pierde el evento. **Accepted risk para Fase 6** — outbox es Fase 8.

**Alternativa considerada:** transactional Kafka producer + JPA transaction. Demasiado para esta fase y agrega complejidad de configuración (chained transaction manager).

### 4. Falla del publish NO rompe el upload
Si `kafkaTemplate.send(...).get(timeout)` lanza:
- Loguear ERROR con `documentId` + `correlationId`.
- Devolver normalmente el `Document` al controller (response 201).

**Por qué:** el contrato del upload con el cliente ya se cumplió (archivo en MinIO, metadata en BD). El processing interno corre igual gracias al `ApplicationEventPublisher`. El evento Kafka es señal adicional para subscribers externos, no es la fuente de verdad del processing en esta fase.

**Trade-off:** los suscriptores externos pueden perder el evento. Aceptado hasta que llegue el outbox.

### 5. `correlationId` opcional vía header
- `POST /documents` lee header `X-Correlation-Id` (UUID-ish, lo persistimos como String).
- Si no viene o no es parseable, se genera un `UUID.randomUUID().toString()`.
- Se mete en MDC `correlationId` durante el handling del request (filtro `CorrelationIdFilter`).
- Se incluye en el envelope Kafka.

**Por qué:** habilita trazabilidad cross-service desde antes de que existan otros servicios. Costo es bajo y futuro grande.

### 6. Envelope JSON
```json
{
  "eventId": "<uuid>",
  "type": "DocumentUploaded",
  "documentId": "<uuid>",
  "occurredAt": "2026-05-17T22:31:00Z",
  "correlationId": "<uuid>"
}
```
- `eventId` único por evento (no por documento). Permite idempotencia downstream.
- `type` literal "DocumentUploaded". Cuando agreguemos otros tipos al mismo topic (no en esta fase), discriminamos por `type`.
- Serialización Jackson, usando el `ObjectMapper` autoconfigurado (mismo que la Fase 5).
- `occurredAt` ISO-8601, `WRITE_DATES_AS_TIMESTAMPS=false` (ya configurado en Fase 5).

### 7. Topic config
- Nombre: `document.uploaded`. Configurable vía `app.kafka.topics.document-uploaded`.
- 3 particiones, replication factor 1 en docker-compose (broker único).
- Auto-create activado para dev. Para integration tests con Testcontainers, declarar el topic vía `KafkaAdmin` / `NewTopic` @Bean para evitar race del auto-create.
- Producer: `acks=all`, `enable.idempotence=true` (default desde Kafka 3.x), `linger.ms=10`.

### 8. docker-compose Kafka
Bitnami Kafka KRaft (sin Zookeeper) para reducir contenedores. Imagen `bitnami/kafka:3.7`. Opcionalmente agregamos `kafka-ui` (`provectuslabs/kafka-ui`) para inspección manual.

**Alternativa considerada:** Confluent CP. Más pesado, más memoria, mismo resultado funcional. Bitnami KRaft es suficiente.

## Risks / Trade-offs

- **[Pérdida de eventos si Kafka cae antes del publish]** → No mitigado en esta fase. Outbox pattern queda como follow-up. Para Fase 6 es aceptable porque el processing interno no depende del evento todavía.
- **[Doble fuente de verdad: ApplicationEvent + Kafka event]** → Aceptado mientras Fase 7 no haga el switch. Documentar claramente que el evento Kafka es informativo y el processing real corre por el listener in-process.
- **[Tests de Kafka son lentos]** → `KafkaContainer` añade ~3-5s al startup. Mitigación: un único integration test class para Kafka, reuse del container singleton.
- **[Schema breakage al agregar fields]** → Jackson agrega fields nuevos de forma compatible (consumers ignoran lo desconocido si configuran `FAIL_ON_UNKNOWN_PROPERTIES=false`). Cuando agreguemos un breaking change, evaluamos schema registry.
- **[`correlationId` no validado como UUID]** → Aceptamos cualquier string razonable. Truncar a 64 chars como defensa contra inputs gigantes. Si en el futuro queremos UUID estricto, lo endurecemos.
- **[`enable.idempotence=true` requiere `acks=all`]** → Compatible con nuestra config; documentado en application.yml.
