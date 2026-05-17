## Why

La Fase 3 dejó el pipeline procesando PDF y Word, pero las imágenes (`image/png`, `image/jpeg`) se aceptan en el upload y terminan en `FAILED` con `errorMessage = "OCR not implemented yet"`. Para cumplir el objetivo del proyecto (extraer texto desde PDF, Word **e imágenes**) hace falta sumar OCR. Encarar esto ahora habilita además la futura extracción estructurada (Fase 5) sobre documentos escaneados.

## What Changes

- Agregar Tess4J (binding Java de Tesseract) como dependencia y resolver `tessdata` por configuración.
- Introducir una interfaz `OcrService` para desacoplar el motor de OCR del extractor, con una implementación inicial `TesseractOcrService`. Deja la puerta abierta a `AwsTextractOcrService` sin tocar el resto del flujo.
- Crear `ImageOcrTextExtractor` que implemente `DocumentTextExtractor`, soporte `image/png` y `image/jpeg`, y delegue en `OcrService`.
- Registrarlo en `TextExtractorRegistry` junto a `PdfTextExtractor` y `WordTextExtractor`.
- Reemplazar el comportamiento actual de "imágenes fallan con `OCR not implemented yet`" por extracción real: las imágenes válidas pasan a `PROCESSED` con `raw_text` poblado.
- Mapear errores del motor OCR a `TextExtractionException` para reusar la transición a `FAILED` con `errorMessage` acotado.

## Capabilities

### New Capabilities
<!-- ninguna -->

### Modified Capabilities
- `document-processing`: la extracción de texto se extiende a `image/png` e `image/jpeg`; se elimina el requirement "Images (PNG/JPEG) fail until OCR is available" y se reemplaza por uno que exige OCR exitoso. El resto del lifecycle (status, idempotencia, persistencia en `document_result`, contratos HTTP) no cambia.

## Impact

- **Código nuevo**: `document/processing/ImageOcrTextExtractor.java`, `document/processing/ocr/OcrService.java`, `document/processing/ocr/TesseractOcrService.java`, `config/OcrProperties.java`.
- **Código modificado**: `TextExtractorRegistry` (registro del nuevo extractor), tests de `DocumentProcessingService` que hoy verifican el caso "PNG → FAILED".
- **Dependencias**: agregar `net.sourceforge.tess4j:tess4j` al `pom.xml`. Requiere `tessdata` accesible (path configurable vía `app.ocr.tessdata-path`, idioma por defecto `eng`).
- **Infra local**: documentar en `docker-compose.yml` / README cómo proveer `tessdata` (volumen o variable de entorno). No se agrega un contenedor nuevo en esta fase.
- **Tests**: nuevos unit tests con una imagen de prueba pequeña (texto conocido) y test de integración que verifica el flujo end-to-end image → `PROCESSED` con `raw_text` no vacío.
