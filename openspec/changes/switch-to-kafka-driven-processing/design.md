## Context

Estado pre-switch:
- `DocumentUploadService.upload(...)` termina con DOS publicaciones: `applicationEventPublisher.publishEvent(new DocumentUploadedEvent(id))` (intra-JVM) **y** `kafkaEventPublisher.publishUploaded(...)` (Kafka).
- `DocumentProcessingListener` (`@EventListener @Async("documentProcessingExecutor")`) escucha la señal in-process y llama a `DocumentProcessingService.process(id)`.
- El evento Kafka existe pero no tiene suscriptores.

Fase 6 estableció el envelope Kafka (`DocumentUploadedKafkaEvent` con `eventId`, `type`, `documentId`, `occurredAt`, `correlationId`) y la infraestructura de testing con `ConfluentKafkaContainer`. La capability `document-processing` ya tiene un requirement de idempotencia para `UPLOADED → PROCESSING` (lo que necesitamos para tolerar at-least-once).

## Goals / Non-Goals

**Goals:**
- El procesamiento se dispara exclusivamente al consumir un mensaje de `document.uploaded`.
- Borrar el camino in-process (`@EventListener`, `DocumentUploadedEvent`, `ApplicationEventPublisher` injection).
- Propagar `correlationId` del envelope al MDC durante el handler.
- Mantener todos los integration tests existentes verdes — incluido `DocumentProcessingIntegrationTest`, que ahora valida implícitamente el switch.
- Idempotencia explícita ante redelivery: si `markAsProcessing` devuelve 0 rows, el handler loguea y retorna OK (offset se commitea).

**Non-Goals:**
- NO retry policy con backoff ni DLT — Fase 8.
- NO outbox pattern para garantizar publish-after-commit — Fase 8.
- NO tabla `processed_events` para deduplicación por `eventId` — la idempotencia de status alcanza.
- NO Kafka transactional producer ni consumer — `acks=all` + idempotent producer + RECORD ack alcanza para Fase 7.
- NO separar `document-worker` como módulo Maven aparte — la app sigue siendo una sola. Eso es Fase 10.

## Decisions

### 1. `JsonDeserializer` con tipo objetivo fijo, sin `__TypeId__` headers
El consumer construye `JsonDeserializer` directamente apuntando a `DocumentUploadedKafkaEvent.class`. En Fase 6 setamos `JsonSerializer.setAddTypeInfo(false)` para no escribir headers — ahora consumimos del mismo modo:

```java
JsonDeserializer<DocumentUploadedKafkaEvent> deserializer =
        new JsonDeserializer<>(DocumentUploadedKafkaEvent.class, objectMapper, false);
```

El tercer parámetro `false` desactiva el uso de type headers. Esto significa que cualquier mensaje del topic se decodifica como `DocumentUploadedKafkaEvent` — apropiado mientras el topic transporte UN solo tipo. Cuando aparezcan otros tipos (no en esta fase) reevaluamos.

**Alternativa considerada:** `JsonDeserializer` con `addTrustedPackages("*")` + type headers. Rechazado — type headers acoplan el producer/consumer a una shape de clase fija, complican refactors, y para topics single-type son innecesarios.

### 2. Ack mode RECORD (default de Spring Kafka)
- `enable.auto.commit=false` (es el default de Spring Kafka cuando hay container listener).
- Container ack mode = RECORD: Spring commitea offset tras cada record procesado sin excepción.
- Si el handler tira → no commit → redelivery infinito hasta éxito (o hasta Fase 8 con DLT).

**Por qué RECORD y no BATCH:** procesar un documento puede ser lento (PDF parsing, OCR). No queremos perder progreso si el N-ésimo del batch falla.

### 3. Consumer concurrency = 1 inicialmente
`ConcurrentKafkaListenerContainerFactory.setConcurrency(1)`. Una sola partición se atiende a la vez. Subir a 3 (= particiones del topic) cuando haya volumen real. Propiedad `app.kafka.consumer.concurrency` permite override sin tocar código.

**Trade-off:** menos throughput pero más fácil de razonar sobre orden y ordering issues durante desarrollo.

### 4. Idempotencia: confiar en el CAS existente
`DocumentProcessingService.process(id)` arranca con `documentRepository.markAsProcessing(id, now)` que es `UPDATE document SET status=PROCESSING WHERE id=? AND status=UPLOADED`. Si devuelve 0 (porque ya está PROCESSING/PROCESSED/FAILED), el método retorna early con un debug log.

Esto cubre:
- **Redelivery puro** (Kafka entrega el mismo mensaje 2 veces): segunda corrida hace UPDATE 0 → skip.
- **Crash entre `markAsProcessing` y `markAsProcessed`**: el doc queda en PROCESSING. Al reintentar, el CAS falla. **Aceptado como bug conocido — Fase 8 lo arregla agregando una transición timeout o un job de barrido.**

**Alternativa considerada:** tabla `processed_events(event_id PRIMARY KEY)` consultada al inicio del handler. Rechazada — agrega round-trip + schema migration por una garantía que el CAS ya provee razonablemente.

### 5. `correlationId` en MDC dentro del handler
```java
public void onMessage(DocumentUploadedKafkaEvent event) {
    String corr = event.correlationId() != null ? event.correlationId() : "unknown";
    MDC.put("correlationId", corr);
    try {
        processingService.process(event.documentId());
    } finally {
        MDC.remove("correlationId");
    }
}
```

Esto preserva la traza desde el `POST /documents` (donde el filter lo metió en MDC, se serializó al envelope) hasta el log del processing.

**Trade-off:** los logs internos de `DocumentProcessingService` no usan explícitamente el `correlationId`, sólo dependen de MDC. Si en el futuro cambiamos el formato de log, hay que confirmar que MDC se respeta.

### 6. Switch hard sin feature flag
Borramos `DocumentProcessingListener` + `DocumentUploadedEvent` + el `publishEvent` interno en el mismo commit que agrega el consumer. No hay coexistencia ni flag.

**Por qué:**
- Tener dos caminos abiertos en paralelo crea ventanas donde `process()` corre 2 veces (uno por canal).
- La capability ya tenía idempotencia, así que un release con bug + rollback se recupera fácilmente.
- Proyecto de práctica — gradual rollout no aporta valor real.

### 7. Limpieza de `AsyncConfig` / `AsyncProperties`
Antes de mergear, búsqueda global de `@Async` en main sources. Si el único uso era `DocumentProcessingListener` (lo más probable), se borran las clases de async + properties + el bloque `app.async.*` de `application.yml`. Si quedan otros `@Async` se mantienen.

### 8. Tolerancia a fallas del broker en el lado producer
**Estado actual (Fase 6):** si el broker está caído al publicar, `KafkaDocumentEventPublisher` loguea ERROR y devuelve normal. Upload responde 201, doc queda en `UPLOADED`.

**Consecuencia tras Fase 7:** el doc queda `UPLOADED` **para siempre** hasta que alguien re-emita el evento (no hay listener in-process que lo salve).

**Aceptado como riesgo de Fase 7.** Mitigaciones:
- Fase 8 outbox pattern publicará desde una tabla con retry persistente.
- Operacionalmente, hasta entonces: monitoreo manual de docs `UPLOADED` > N minutos y re-emisión vía endpoint admin (out of scope).

## Risks / Trade-offs

- **[Doc atrapado en `UPLOADED` si Kafka cae justo en el publish]** → Aceptado para esta fase. Resuelto por outbox en Fase 8.
- **[Doc atrapado en `PROCESSING` si la JVM crashea entre status updates]** → Aceptado. Fase 8 agrega timeout-based recovery.
- **[Redelivery infinito si `process()` lanza excepción no transitoria]** → Aceptado. Fase 8 introduce retry-with-backoff y DLT después de N intentos.
- **[Type-info-free deserialization acopla topic a un único tipo]** → Aceptado. Reevaluar cuando aparezcan `DocumentProcessed` o eventos compartidos.
- **[MDC propagation depende de que el handler no escape al pool de listeners en otro thread]** → Spring Kafka invoca el handler en el thread del container; mientras no usemos `@Async` dentro, MDC se preserva. Documentado.
- **[Test de integración existente puede flaky-ear al pasar por Kafka]** → El test ya usa Awaitility con timeout de 15s, suficiente para el roundtrip Kafka local. Si se ve flaky, subir el timeout.
