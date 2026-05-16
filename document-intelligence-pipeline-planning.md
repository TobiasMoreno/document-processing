# Document Intelligence Pipeline - Planning

## Objetivo del proyecto

Construir un sistema backend en **Spring Boot** para procesar documentos de forma asincrónica, extraer texto desde distintos formatos y transformar información no estructurada en datos estructurados.

La idea principal es practicar y demostrar experiencia en:

- Document processing.
- PDF parsing.
- Word parsing.
- OCR.
- Data extraction.
- Event-driven architecture.
- Procesamiento asincrónico.
- Kafka.
- Kubernetes básico.
- Observabilidad backend.

---

## Descripción general

El sistema permitirá subir documentos como PDF, Word o imágenes.  
Luego, en lugar de procesarlos directamente durante el request HTTP, la API publicará un evento en Kafka para que un worker procese el documento en background.

El resultado final será un JSON estructurado con los datos extraídos del documento.

---

## Caso de uso principal

### Flujo esperado

1. El usuario sube un documento.
2. La API valida el archivo.
3. La API guarda metadata del documento en base de datos.
4. La API publica un evento `DocumentUploaded`.
5. Un worker consume el evento.
6. El worker detecta el tipo de documento.
7. El worker extrae texto usando la estrategia correspondiente.
8. El worker intenta extraer datos estructurados.
9. El resultado se guarda en base de datos.
10. La API permite consultar el estado y resultado del procesamiento.

---

## Arquitectura propuesta

```txt
Client
  ↓
document-api
  ↓ guarda metadata
PostgreSQL
  ↓ publica evento
Kafka topic: document.uploaded
  ↓
document-worker
  ↓ extrae texto y datos
PostgreSQL
  ↓ publica evento
Kafka topic: document.processed
  ↓
notification-service / audit-service
```

---

## Servicios

### 1. document-api

Responsabilidades:

- Exponer endpoints HTTP.
- Recibir documentos.
- Validar tipo y tamaño de archivo.
- Guardar metadata.
- Publicar eventos.
- Consultar estado del procesamiento.
- Consultar resultado final.

Endpoints iniciales:

```http
POST /documents
GET /documents/{id}
GET /documents/{id}/status
GET /documents/{id}/result
```

---

### 2. document-worker

Responsabilidades:

- Consumir eventos `DocumentUploaded`.
- Buscar metadata del documento.
- Obtener el archivo.
- Detectar tipo de archivo.
- Extraer texto.
- Extraer datos estructurados.
- Guardar resultado.
- Actualizar estado.
- Publicar evento `DocumentProcessed` o `DocumentProcessingFailed`.

---

### 3. notification-service / audit-service opcional

Responsabilidades:

- Consumir eventos finales.
- Registrar auditoría.
- Simular notificaciones.
- Generar logs de seguimiento.

Este servicio puede agregarse más adelante para practicar múltiples consumidores.

---

## Stack tecnológica recomendada

### Backend

- Java 21.
- Spring Boot.
- Spring Web.
- Spring Validation.
- Spring Data JPA.
- Spring Kafka.
- PostgreSQL.

### Document processing

- Apache PDFBox para PDFs.
- Apache POI para documentos Word.
- Tesseract OCR para imágenes o documentos escaneados.
- Opcional: AWS Textract como implementación cloud futura.

### Messaging

- Apache Kafka.
- Spring Kafka.
- Kafka UI.

### Local environment

- Docker.
- Docker Compose.

### Testing

- JUnit.
- Mockito.
- Testcontainers.
- Spring Boot Test.

### Deployment

- Dockerfile.
- Kubernetes.
- Kind o Minikube.
- kubectl.

---

## Modelo de dominio inicial

### Document

Representa un documento cargado en el sistema.

Campos sugeridos:

```txt
id
originalFilename
contentType
size
storagePath
status
createdAt
updatedAt
processedAt
errorMessage
```

Estados posibles:

```txt
UPLOADED
PROCESSING
PROCESSED
FAILED
```

---

### DocumentResult

Representa el resultado del procesamiento.

Campos sugeridos:

```txt
id
documentId
rawText
documentType
extractedData
createdAt
```

`extractedData` puede guardarse inicialmente como JSON.

---

## Eventos

### DocumentUploaded

Evento publicado cuando un documento fue subido correctamente.

```json
{
  "eventId": "evt-123",
  "type": "DocumentUploaded",
  "documentId": "doc-456",
  "occurredAt": "2026-05-16T10:30:00Z",
  "correlationId": "corr-789"
}
```

---

### DocumentProcessed

Evento publicado cuando un documento fue procesado correctamente.

```json
{
  "eventId": "evt-124",
  "type": "DocumentProcessed",
  "documentId": "doc-456",
  "occurredAt": "2026-05-16T10:31:00Z",
  "correlationId": "corr-789"
}
```

---

### DocumentProcessingFailed

Evento publicado cuando el procesamiento falla.

```json
{
  "eventId": "evt-125",
  "type": "DocumentProcessingFailed",
  "documentId": "doc-456",
  "reason": "Could not extract text from file",
  "occurredAt": "2026-05-16T10:31:00Z",
  "correlationId": "corr-789"
}
```

---

## Estrategia de parsing

La extracción de texto debería estar desacoplada mediante una interfaz.

```java
public interface DocumentTextExtractor {
    boolean supports(String contentType);

    String extract(DocumentFile file);
}
```

Implementaciones posibles:

```txt
PdfTextExtractor
WordTextExtractor
ImageOcrTextExtractor
```

Esto permite aplicar una estrategia distinta según el tipo de archivo.

---

## Estrategia de OCR

Primera etapa:

```txt
Tesseract OCR local
```

Segunda etapa opcional:

```txt
AWS Textract
```

Diseño recomendado:

```java
public interface OcrService {
    String extractText(DocumentFile file);
}
```

Implementaciones futuras:

```txt
TesseractOcrService
AwsTextractOcrService
```

---

## Estrategia de extracción de datos

Primera versión:

- Regex.
- Reglas simples.
- Validaciones.
- DTOs específicos según tipo de documento.

Campos iniciales para practicar:

```txt
invoiceNumber
date
amount
currency
providerName
taxId
```

Ejemplo de resultado:

```json
{
  "documentType": "INVOICE",
  "extractedData": {
    "invoiceNumber": "0001-00012345",
    "date": "2026-05-16",
    "amount": 125000.50,
    "currency": "ARS",
    "providerName": "Empresa S.A."
  }
}
```

Segunda versión opcional:

- Usar un modelo de IA para transformar texto libre en JSON.
- Validar siempre la respuesta con DTOs y reglas backend.
- No confiar ciegamente en la salida del modelo.

---

## Manejo asincrónico

El endpoint `POST /documents` no debería procesar el documento directamente.

Debería:

1. Validar archivo.
2. Guardar metadata.
3. Guardar archivo en storage local o S3-compatible.
4. Publicar evento `DocumentUploaded`.
5. Responder rápido con `documentId` y estado `UPLOADED`.

Respuesta esperada:

```json
{
  "documentId": "doc-456",
  "status": "UPLOADED"
}
```

---

## Kafka

### Topics iniciales

```txt
document.uploaded
document.processed
document.processing-failed
```

### Conceptos a practicar

- Producer.
- Consumer.
- Consumer group.
- Offset.
- Retry.
- Dead Letter Topic.
- Idempotencia.
- Correlation ID.
- Eventual consistency.
- Observabilidad del flujo.

---

## Idempotencia

El worker debería evitar procesar dos veces el mismo documento si recibe el mismo evento más de una vez.

Estrategias posibles:

- Verificar estado actual del documento antes de procesar.
- Si está `PROCESSED`, ignorar el evento.
- Si está `PROCESSING`, evaluar si continuar o descartar.
- Guardar `eventId` procesados en una tabla opcional.
- Diseñar el procesamiento para que sea seguro ante duplicados.

---

## Manejo de errores

Casos a considerar:

- Archivo inválido.
- Tipo de archivo no soportado.
- Error leyendo PDF.
- Error leyendo Word.
- OCR fallido.
- Kafka no disponible.
- Base de datos no disponible.
- Documento duplicado.
- Timeout procesando documento.
- Error de extracción de datos.

Estados ante error:

```txt
FAILED
```

Guardar mensaje técnico acotado:

```txt
errorMessage
```

No exponer detalles sensibles al cliente.

---

## Observabilidad

Agregar desde el inicio:

- Logs estructurados.
- `correlationId`.
- `documentId` en cada log relevante.
- Tiempo de procesamiento.
- Resultado del procesamiento.
- Motivo de error.

Ejemplo de log conceptual:

```json
{
  "level": "INFO",
  "message": "Document processing started",
  "documentId": "doc-456",
  "correlationId": "corr-789",
  "service": "document-worker"
}
```

Métricas futuras:

- Cantidad de documentos subidos.
- Cantidad de documentos procesados.
- Cantidad de errores.
- Tiempo promedio de procesamiento.
- Cantidad de mensajes enviados a DLT.
- Latencia por tipo de documento.

---

## Testing

### Unit tests

Cubrir:

- Validación de archivos.
- Extractores de texto.
- Extractores de datos.
- Mapeos entre entities y DTOs.
- Servicios de dominio.

### Integration tests

Cubrir:

- Upload de documentos.
- Persistencia en PostgreSQL.
- Publicación de eventos Kafka.
- Consumo de eventos.
- Procesamiento completo.

Herramienta recomendada:

```txt
Testcontainers
```

Contenedores útiles:

```txt
PostgreSQL
Kafka
```

---

## Docker Compose inicial

Servicios sugeridos:

```txt
postgres
kafka
kafka-ui
document-api
document-worker
```

Opcional:

```txt
minio
```

MinIO puede usarse como alternativa local compatible con S3 para guardar archivos.

---

## Kubernetes

Primera etapa:

Desplegar solamente:

```txt
document-api
document-worker
```

Recursos mínimos:

```txt
Deployment
Service
ConfigMap
Secret
Liveness Probe
Readiness Probe
```

Más adelante:

```txt
PostgreSQL
Kafka
Ingress
Horizontal Pod Autoscaler
```

---

## Roadmap de implementación

### Fase 1 - API básica

Objetivo:

```txt
Subir documentos y guardar metadata
```

Tareas:

- Crear proyecto Spring Boot.
- Crear entity `Document`.
- Crear repository.
- Crear endpoint `POST /documents`.
- Validar tipo y tamaño de archivo.
- Guardar archivo localmente.
- Persistir metadata.
- Devolver `documentId`.

---

### Fase 2 - Consulta de estado

Objetivo:

```txt
Consultar estado y metadata del documento
```

Tareas:

- Crear endpoint `GET /documents/{id}`.
- Crear endpoint `GET /documents/{id}/status`.
- Modelar estados.
- Agregar manejo de errores para documento inexistente.

---

### Fase 3 - PDF y Word parsing

Objetivo:

```txt
Extraer texto de PDF y Word
```

Tareas:

- Agregar Apache PDFBox.
- Agregar Apache POI.
- Crear interfaz `DocumentTextExtractor`.
- Crear `PdfTextExtractor`.
- Crear `WordTextExtractor`.
- Crear tests unitarios con documentos de ejemplo.

---

### Fase 4 - OCR

Objetivo:

```txt
Extraer texto desde imágenes
```

Tareas:

- Integrar Tesseract OCR.
- Crear `ImageOcrTextExtractor`.
- Manejar errores de OCR.
- Agregar pruebas con imágenes simples.

---

### Fase 5 - Data extraction

Objetivo:

```txt
Convertir texto en datos estructurados
```

Tareas:

- Crear `DocumentDataExtractor`.
- Crear extractor inicial para facturas.
- Extraer fecha, monto, moneda, proveedor y número.
- Validar resultado.
- Guardar JSON en `DocumentResult`.

---

### Fase 6 - Kafka producer

Objetivo:

```txt
Publicar evento cuando se sube un documento
```

Tareas:

- Agregar Spring Kafka.
- Configurar Kafka producer.
- Crear evento `DocumentUploaded`.
- Publicar evento desde `document-api`.
- Agregar `correlationId`.

---

### Fase 7 - Kafka consumer / worker

Objetivo:

```txt
Procesar documentos en background
```

Tareas:

- Crear `document-worker`.
- Configurar Kafka consumer.
- Consumir `DocumentUploaded`.
- Actualizar estado a `PROCESSING`.
- Ejecutar parsing.
- Guardar resultado.
- Actualizar estado a `PROCESSED`.
- Publicar `DocumentProcessed`.

---

### Fase 8 - Error handling, retries y DLT

Objetivo:

```txt
Hacer el procesamiento más resiliente
```

Tareas:

- Configurar retries.
- Configurar Dead Letter Topic.
- Manejar errores controlados.
- Marcar documento como `FAILED`.
- Guardar error acotado.
- Evitar duplicados con idempotencia básica.

---

### Fase 9 - Observabilidad

Objetivo:

```txt
Hacer visible el flujo completo
```

Tareas:

- Agregar logs estructurados.
- Propagar `correlationId`.
- Loguear tiempos de procesamiento.
- Agregar métricas básicas.
- Preparar dashboard futuro.

---

### Fase 10 - Docker y Kubernetes

Objetivo:

```txt
Empaquetar y desplegar servicios
```

Tareas:

- Crear Dockerfile para `document-api`.
- Crear Dockerfile para `document-worker`.
- Crear docker-compose.
- Crear manifests Kubernetes.
- Configurar Deployment.
- Configurar Service.
- Configurar ConfigMap.
- Configurar Secret.
- Configurar readiness probe.
- Configurar liveness probe.

---

## Backlog técnico

### Mejoras futuras

- Soporte para archivos `.xlsx`.
- Clasificación automática de documentos.
- Extracción con IA.
- Integración con AWS Textract.
- Storage con MinIO o S3.
- Outbox Pattern.
- Schema Registry.
- Avro o Protobuf.
- DLQ dashboard.
- Métricas con Prometheus.
- Trazabilidad distribuida con OpenTelemetry.
- Deploy en AWS.
- CI/CD con GitHub Actions.

---

## Preguntas técnicas para defender en entrevista

### Document processing

- ¿Cómo detectás si un PDF es digital o escaneado?
- ¿Qué hacés si el OCR devuelve texto incorrecto?
- ¿Cómo validás los datos extraídos?
- ¿Cómo manejarías distintos tipos de documentos?
- ¿Dónde guardarías el archivo original?

### Async / Event-driven

- ¿Por qué no procesar el documento en el request HTTP?
- ¿Qué pasa si el worker falla?
- ¿Cómo evitás procesar dos veces el mismo evento?
- ¿Cómo trazás una operación entre servicios?
- ¿Qué diferencia hay entre request-response y eventos?

### Kafka

- ¿Qué es un topic?
- ¿Qué es un consumer group?
- ¿Qué es un offset?
- ¿Cómo manejás retries?
- ¿Qué es una DLT?
- ¿Kafka garantiza orden?
- ¿Cuándo no usarías Kafka?

### Kubernetes

- ¿Qué es un pod?
- ¿Qué es un deployment?
- ¿Qué diferencia hay entre ConfigMap y Secret?
- ¿Qué son readiness y liveness probes?
- ¿Cómo revisarías logs de un servicio?
- ¿Cómo escalarías un deployment?

---

## Respuesta objetivo para entrevista

> Armé un pipeline de procesamiento documental con Spring Boot. La API recibe documentos PDF, Word o imágenes, guarda metadata y publica un evento en Kafka para procesarlo de forma asincrónica.
>
> El worker consume el evento, extrae texto usando PDFBox, Apache POI u OCR según el tipo de archivo, intenta estructurar campos relevantes y actualiza el estado del documento en PostgreSQL.
>
> También trabajé conceptos como retries, idempotencia, correlation IDs, logs estructurados, manejo de errores y despliegue básico en Kubernetes con deployments, services, configmaps, secrets y health checks.

---

## Criterio de éxito del proyecto

El proyecto se considera funcional cuando:

- Se puede subir un documento.
- El request responde rápido sin procesar todo en línea.
- Se publica un evento Kafka.
- Un worker consume el evento.
- Se extrae texto del documento.
- Se genera un resultado estructurado.
- Se puede consultar el estado.
- Se manejan errores.
- Hay logs con `documentId` y `correlationId`.
- Los servicios pueden correr con Docker Compose.
- Al menos `document-api` y `document-worker` tienen manifests Kubernetes básicos.

---

## Enfoque recomendado

No intentar resolver todo de una vez.

Primero construir una versión simple y funcional:

```txt
Upload → metadata → parsing PDF → result
```

Después evolucionar hacia:

```txt
Upload → Kafka → worker → extraction → result → observability → Kubernetes
```

La prioridad debería ser demostrar criterio backend, separación de responsabilidades, asincronismo, resiliencia y capacidad de convertir documentos en datos útiles.
