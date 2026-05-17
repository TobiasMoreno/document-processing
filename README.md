# document-processing

Pipeline backend en Spring Boot para procesar documentos (PDF, Word, imágenes) de forma asincrónica vía Kafka y extraer datos estructurados (facturas C argentinas).

Estado actual: Fases 1–7 del [planning](./document-intelligence-pipeline-planning.md) completas. Próxima: Fase 8 (retries + DLT + outbox).

---

## Stack

- Java 21, Spring Boot 4.0.6
- PostgreSQL 16 (metadata + resultados)
- MinIO (storage S3-compatible para los archivos)
- Kafka (Confluent CP 7.6.0) para eventos
- PDFBox, Apache POI, Tess4J (OCR)
- Testcontainers para integration tests

---

## Levantar la infra local

Todo el stack corre con docker-compose:

```bash
docker-compose up -d
```

Servicios:

| Servicio | URL host | Para qué |
|---|---|---|
| postgres | `localhost:5432` (user/pass/db: `documents`) | Metadata y resultados |
| minio | `http://localhost:9000` (API) / `http://localhost:9001` (UI) | Storage de archivos. Bucket `documents` creado por `minio-init` |
| kafka | `localhost:9092` (host) / `kafka:9092` (Docker network) | Broker del topic `document.uploaded` |
| kafka-ui | `http://localhost:8080` | UI web para inspeccionar topics/mensajes |
| minio-init | — | Job one-shot que crea el bucket y termina (exit 0 es OK) |

Verificá:

```bash
docker-compose ps
```

Bajar:

```bash
docker-compose down       # preserva volúmenes
docker-compose down -v    # resetea postgres + minio + kafka data
```

### Por qué Confluent y no Bitnami

A partir de mediados de 2025 Bitnami movió sus imágenes gratuitas a `bitnamilegacy/`. Usar `confluentinc/cp-kafka` evita esa dependencia y matchea la imagen que ya usamos en los integration tests con Testcontainers.

### Detalle de listeners de Kafka

El broker expone dos listeners:

- `PLAINTEXT://kafka:9092` — para clientes dentro de la red Docker (p. ej. `kafka-ui`).
- `PLAINTEXT_HOST://localhost:9092` — para clientes en el host (p. ej. la app corriendo con `./mvnw spring-boot:run`). Internamente el container escucha `29092` y el mapping `9092:29092` lo expone como `9092` al host.

Si necesitás conectar otro cliente desde el host, usá `localhost:9092`.

---

## Configurar OCR (opcional)

Sólo si vas a procesar imágenes PNG/JPEG. Para PDF y Word no hace falta.

1. Instalar Tesseract:
   - Windows: instalador oficial (https://github.com/UB-Mannheim/tesseract/wiki)
   - Linux: `apt install tesseract-ocr`
2. Setear la variable de entorno apuntando al directorio con los `.traineddata`:

   ```powershell
   # Windows PowerShell
   $env:APP_OCR_TESSDATA_PATH = "C:\Program Files\Tesseract-OCR\tessdata"
   ```

   ```bash
   # Linux/macOS
   export APP_OCR_TESSDATA_PATH=/usr/share/tesseract-ocr/4.00/tessdata
   ```

3. (Opcional) Override idioma con `APP_OCR_LANGUAGE` (default `eng`).

---

## Arrancar la app

Con la infra ya levantada:

```bash
./mvnw spring-boot:run
```

⚠️ **Conflicto de puertos**: kafka-ui ocupa `8080`. Spring Boot por default también arranca en `8080`. Hay dos opciones:

- **Recomendado**: pasarle un puerto distinto a la app:

  ```bash
  ./mvnw spring-boot:run "-Dspring-boot.run.arguments=--server.port=8090"
  ```

  La app queda en `http://localhost:8090`.

- O agregar a `application.yml`:

  ```yaml
  server:
    port: 8090
  ```

En los logs deberías ver, además del clásico `Started TobiasMorenoApplication`:

```
partitions assigned: [document.uploaded-0, document.uploaded-1, document.uploaded-2]
```

Eso confirma que el consumer Kafka quedó suscrito al topic.

---

## Probar el flujo end-to-end

Asumiendo que la app corre en `localhost:8090`.

### 1. Subir un documento

```bash
curl -X POST http://localhost:8090/documents \
  -F "file=@02-2026.pdf" \
  -H "X-Correlation-Id: prueba-1"
```

Respuesta esperada:

```json
{ "documentId": "<uuid>", "status": "UPLOADED" }
```

### 2. Inspeccionar el evento en Kafka UI

Abrí http://localhost:8080 → **Topics** → `document.uploaded` → **Messages**.

Vas a ver un mensaje con:

- **Key**: el `documentId`
- **Value**: JSON `{ "eventId": "...", "type": "DocumentUploaded", "documentId": "...", "occurredAt": "...", "correlationId": "prueba-1" }`

### 3. Verificar que el consumer lo procesó

En los logs de la app:

```
Processed document <uuid>
```

### 4. Consultar el estado

```bash
curl http://localhost:8090/documents/<uuid>
```

Esperá `"status": "PROCESSED"`.

### 5. Ver los datos estructurados (si subiste una factura)

```bash
docker exec -it document-processing-postgres-1 psql -U documents -d documents \
  -c "SELECT document_type, extracted_data FROM document_result;"
```

Para una Factura C deberías ver `INVOICE` + un JSON con `invoiceNumber`, `total`, CUITs, `cae`, etc.

---

## Verificar el fail-mode de Kafka (limitación conocida)

Si Kafka cae justo cuando un upload publica, el doc queda en `UPLOADED` para siempre (no hay outbox todavía — Fase 8).

```bash
docker-compose stop kafka
curl -X POST http://localhost:8090/documents -F "file=@02-2026.pdf"
# La respuesta sigue siendo 201; los logs muestran ERROR del KafkaDocumentEventPublisher
docker-compose start kafka
# El doc NO se procesa automáticamente — comportamiento esperado para esta fase.
```

---

## Tests

```bash
./mvnw test
```

86 tests, 1 skip preexistente (test live de Tesseract que requiere `APP_OCR_TESSDATA_PATH`).

Los integration tests levantan sus propios containers con Testcontainers — no usan el `docker-compose.yml`. Sólo necesitan Docker Desktop corriendo.

---

## Comandos útiles

```bash
# Logs de Kafka
docker-compose logs -f kafka

# Logs de la app de procesamiento (si corre en background, mejor dejarla en foreground)

# Inspeccionar Postgres
docker exec -it document-processing-postgres-1 psql -U documents -d documents

# Inspeccionar MinIO via mc (cliente CLI)
docker run --rm -it --network=host minio/mc \
  alias set local http://localhost:9000 minioadmin minioadmin \&\& \
  mc ls local/documents

# Consumir el topic manualmente
docker exec -it document-processing-kafka-1 \
  kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic document.uploaded --from-beginning
```

---

## Estructura del proyecto

```
src/main/java/document_processing/tobias_moreno/
├── config/              # AppConfig, KafkaConfig, KafkaProperties, MinioProperties, etc.
├── document/
│   ├── DocumentController.java
│   ├── DocumentUploadService.java
│   ├── DocumentQueryService.java
│   ├── event/           # DocumentUploadedKafkaEvent (envelope), Publisher interface + impl
│   ├── processing/
│   │   ├── DocumentProcessingService.java
│   │   ├── DocumentUploadedKafkaConsumer.java   # entry point del procesamiento
│   │   ├── data/        # InvoiceDataExtractor, DocumentDataExtractorRegistry, DTOs
│   │   ├── ocr/         # OcrService + TesseractOcrService
│   │   └── *TextExtractor.java
│   └── result/          # DocumentResult entity + repo
├── storage/             # ObjectStorage abstraction, MinIO impl
└── web/                 # CorrelationIdFilter, GlobalExceptionHandler
```

OpenSpec specs y changes archivadas en `openspec/`.

---

## Documentación adicional

- [`document-intelligence-pipeline-planning.md`](./document-intelligence-pipeline-planning.md) — planning completo del pipeline por fases.
- [`openspec/specs/`](./openspec/specs/) — specs activas (`document-upload`, `document-storage`, `document-query`, `document-processing`).
- [`openspec/changes/archive/`](./openspec/changes/archive/) — changes ya implementadas con proposal/design/specs/tasks.
