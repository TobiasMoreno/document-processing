## Context

Fase 3 del Document Intelligence Pipeline. Sobre la base de Fases 1 y 2 (upload + query) hay que añadir extracción de texto y un disparador asincrónico que anticipe la migración a Kafka.

Constraints heredados:
- Java 21, Spring Boot 4.0.6, Postgres, MinIO.
- `Document` entity ya con estados `UPLOADED | PROCESSING | PROCESSED | FAILED`.
- `ObjectStorage` actual solo soporta `store` + `delete`.
- `DocumentUploadService` ya retorna un `Document` persistido tras subir bytes a MinIO.

## Goals / Non-Goals

**Goals:**
- Disparar la extracción sin bloquear el POST.
- Extraer texto plano de PDF y Word de manera robusta a errores (un PDF corrupto → `FAILED`, no derriba el proceso).
- Anticipar Kafka: el contrato in-process (publisher → listener) debe ser fácil de reemplazar.
- Persistir `rawText` en una tabla preparada para Fase 5 (`document_result`).
- No tocar contratos HTTP existentes.

**Non-Goals:**
- OCR (Fase 4).
- Data extraction estructurada (Fase 5).
- Reprocesamiento por API.
- Cola persistente o garantías at-least-once (eso llega con Kafka).
- Backpressure sofisticado.
- Exponer `rawText` en `GET /documents/{id}` (Fase 5).

## Decisions

### 1. Trigger: `ApplicationEventPublisher` + `@Async @EventListener`
**Decisión**: tras el save exitoso en `DocumentUploadService.upload(...)`, publicar `DocumentUploadedEvent(UUID documentId)`. Un componente `DocumentProcessingListener` con `@Async` lo consume y delega en `DocumentProcessingService.process(UUID)`.

**Por qué**:
- El listener async corre fuera de la tx del POST: el documento ya está commiteado cuando el listener lee.
- Cambiar a Kafka es localizado: el publisher pasa de `ApplicationEventPublisher` a `KafkaTemplate`; el consumidor de Kafka recibe el mismo payload y delega en el mismo `DocumentProcessingService`. La lógica de extracción no cambia.
- `@TransactionalEventListener(phase = AFTER_COMMIT)` también se evaluó: garantiza que el evento solo se procese si la tx commitea, pero `@Async` ya logra el desacoplamiento de hilo y la publicación ocurre **después** del `repository.save(...)` que ya commitea por defecto en Spring Data. Mantenemos `@EventListener + @Async` por simplicidad.

### 2. Status transitions con CAS optimista (compare-and-set)
**Decisión**: la transición `UPLOADED` → `PROCESSING` se hace con un UPDATE condicional via Spring Data:
```sql
UPDATE document SET status='PROCESSING', updated_at=now()
WHERE id=? AND status='UPLOADED';
```
Implementado con un método `@Modifying @Query` que retorna el `int` de filas afectadas. Si retorna 0, alguien más tomó el evento o el documento está en otro estado: el listener abandona silenciosamente (idempotencia).

**Por qué**: aunque hoy solo hay un proceso, la duplicación de eventos puede ocurrir (reentregas en el futuro con Kafka, o reintentos manuales). Esta primitiva ya sirve para idempotencia que la Fase 8 va a formalizar.

### 3. Persistencia de `DocumentResult` como tabla separada desde ya
**Decisión**: crear tabla `document_result` (`document_id` PK + FK a `document(id)` con ON DELETE CASCADE, `raw_text TEXT`, `document_type VARCHAR(64) NULL`, `extracted_data JSONB NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`).

Relación: 1:1 entre `Document` y `DocumentResult` (PK compartida).

**Alternativa descartada**: agregar `raw_text` como columna en `document`. Más simple ahora pero garantiza una migración tipo "split table" en Fase 5. Hacerla una vez ahora gasta menos energía total.

### 4. `ObjectStorage.read(String key)` retorna `InputStream` que el caller debe cerrar
**Decisión**: la nueva firma es `InputStream read(String key)`. Errores (no existe, sin conexión) van como `StorageException`.

**Por qué**: PDFBox y POI consumen streams; pedirle al storage que lea todo a memoria sería innecesario y bloquea archivos grandes. Devolver `InputStream` directamente del cliente MinIO mantiene el zero-copy.

El caller usa try-with-resources. Si un test mock devuelve un stream, no necesita lifecycle especial.

### 5. Selección de extractor por content-type
**Decisión**: `TextExtractorRegistry` recibe la lista de `DocumentTextExtractor` via constructor injection (Spring inyecta todos los `@Component`). Iterates `supports(contentType)`. Si ninguno aplica → throws `UnsupportedDocumentTypeException` (subtipo de `TextExtractionException`).

Para `image/png` y `image/jpeg` hay dos caminos posibles:
- (a) no registrar un extractor para imágenes → el registry tira `UnsupportedDocumentTypeException`; el listener mapea eso a `FAILED` con mensaje "OCR not implemented yet".
- (b) registrar un stub `ImagePlaceholderExtractor` que siempre tira una excepción específica.

**Elegimos (a)**: menos código, más explícito. El mensaje "OCR not implemented yet" lo arma el listener al detectar `UnsupportedDocumentTypeException`.

### 6. PDFBox 3.x API (`org.apache.pdfbox.Loader`)
**Decisión**: usar `Loader.loadPDF(RandomAccessRead)` o `Loader.loadPDF(byte[])` (PDFBox 3.x deprecó `PDDocument.load`). `PDFTextStripper.getText(document)`.

**Riesgo**: PDFBox carga el doc completo en memoria. Para archivos cerca del límite (10 MB) está bien. Marcar como deuda si subimos el límite.

### 7. POI: WXPFExtractor para .docx, WordExtractor para .doc
**Decisión**: `org.apache.poi.xwpf.extractor.XWPFWordExtractor` para `.docx`; `org.apache.poi.hwpf.extractor.WordExtractor` (de `poi-scratchpad`) para `.doc`. El `WordTextExtractor` distingue por content-type.

**Por qué**: una sola clase Java atendiendo ambos formatos evita la duplicación de configuración y dejar dos beans Spring para Word.

### 8. Thread pool del listener
**Decisión**: bean `ThreadPoolTaskExecutor` propio (no usar el default de Spring), con:
- core-size: 2
- max-size: 4
- queue-capacity: 50
- thread-name-prefix: `doc-proc-`
- rejection policy: `CallerRunsPolicy` (si el queue se llena, el publisher absorbe el trabajo — degrada el POST pero no pierde eventos).

Configurable via `app.processing.async.*` properties.

**Por qué**: previene runaway threads y deja un knob obvio para tunear.

### 9. Manejo de errores del listener
**Decisión**: cualquier excepción dentro del listener se captura, se loguea (con `documentId`), y se persiste el documento como `FAILED` con `error_message` truncado a 1024 chars y **sin** stack trace.

El error_message es **categórico**, no técnico crudo:
- `UnsupportedDocumentTypeException` → "OCR not implemented yet" para imágenes; "Unsupported document type" para otros.
- `TextExtractionException` con causa `IOException` de PDFBox → "Failed to read PDF content".
- Excepción genérica → "Processing failed".

**Por qué**: separar la observabilidad (log con stack trace para el operador) del contrato persistido (mensaje seguro y descriptivo para el cliente cuando se exponga en `GET /documents/{id}` — que ya devuelve `errorMessage`).

### 10. Eventos in-process, sin garantías at-least-once
**Decisión**: en esta fase, si el proceso se reinicia entre el publish y el handle, el evento se pierde. No hay outbox ni reintento automático.

**Por qué**: el outbox pattern es Fase 6 (cuando entra Kafka). Documentar la limitación. Si pasa, el operador puede reprocesar manualmente (vía un endpoint o script futuro — no en esta change).

### 11. Idempotencia básica
**Decisión**: la CAS de la decisión 2 ya cubre el caso "evento duplicado". El listener solo procesa si pudo transicionar `UPLOADED → PROCESSING`. Si recibió el evento dos veces, el segundo es no-op.

**Por qué**: zero costo, gran ganancia. Es la base de la idempotencia que Fase 8 va a formalizar para Kafka.

### 12. Tests async con Awaitility
**Decisión**: integration tests esperan hasta 10 segundos con `Awaitility.await().atMost(10, SECONDS).until(...)` que el documento llegue al estado esperado.

**Por qué**: alternativa (`Thread.sleep`) es frágil; bloquear hasta sincronizar el listener con un `CountDownLatch` requiere inyectar test hooks en producción. Awaitility ya está en el ecosistema Spring Boot.

## Risks / Trade-offs

- **Pérdida de eventos in-process si el proceso muere** → aceptado para esta fase; se resuelve con Kafka + outbox en Fase 6/8.
- **`InputStream` desde MinIO se queda colgado si el caller no lo cierra** → mitigación: el service usa try-with-resources; test que verifica el cierre podría añadirse pero no es crítico (el bug se notaría con cualquier integration test largo).
- **PDFBox y POI son librerías pesadas** → trade-off por capacidad real. Acceptable para Fase 3; si más adelante se quiere reducir el footprint, considerar Apache Tika como wrapper unificado.
- **Imágenes con extractor inexistente caen como `FAILED`** → es ruido en métricas hasta Fase 4. Documentado en `errorMessage` y se puede reprocesar mediante reupload una vez OCR exista.
- **Sin reintentos automáticos** → un fallo transitorio de MinIO durante la lectura deja al documento como `FAILED`. Aceptable hasta Fase 8 (retries + DLT con Kafka).
- **Thread pool acotado puede ser cuello de botella** → mitigado con `CallerRunsPolicy` y métricas futuras; en esta fase los volúmenes son experimentales.

## Migration Plan

- Migración `V2__create_document_result_table.sql` se aplica automáticamente al primer arranque.
- Documentos preexistentes en estado `UPLOADED` quedan así (no se reprocesan retroactivamente). No hay datos reales todavía; aceptable.
- Rollback: bajar la app y eliminar la tabla `document_result` con un script ad-hoc si fuera necesario. Flyway no soporta rollback automático.

## Open Questions

- ¿Conviene exponer un endpoint admin para forzar reprocesamiento? — Decidido: **no** en esta change. Cuando Fase 4 (OCR) habilite imágenes, esos documentos ya marcados `FAILED` se reprocesarán manualmente (script o re-upload). Si emerge la necesidad real, se puede sumar después como una change pequeña.
- ¿`raw_text` debería normalizarse (trim, dedup espacios)? — Decidido: **no** en Fase 3. Guardar el texto crudo tal cual lo devuelve el extractor. Fase 5 puede normalizar al hacer regex si necesita.
