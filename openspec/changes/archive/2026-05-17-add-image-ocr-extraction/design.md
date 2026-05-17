## Context

`TextExtractorRegistry` ya resuelve qué `DocumentTextExtractor` usar buscando por `contentType`. Hoy hay dos implementaciones (`PdfTextExtractor`, `WordTextExtractor`) y las imágenes terminan en `UnsupportedDocumentTypeException` o en el placeholder "OCR not implemented yet" (cubierto explícitamente por la spec de `document-processing`). El motor de OCR es una pieza pesada (binarios nativos, modelos de lenguaje, posible salto a la nube) y no debería filtrarse al resto del dominio.

El proyecto se ejecuta tanto local (dev box Windows + `docker-compose`) como dentro de un contenedor JVM (futuro K8s), por lo que la elección de OCR debe tolerar ambos escenarios sin obligar a empacar binarios pesados en la imagen base.

## Goals / Non-Goals

**Goals:**
- Procesar `image/png` e `image/jpeg` extrayendo texto vía OCR y persistiendo `raw_text`.
- Mantener el flujo de status, idempotencia y manejo de errores idéntico al de PDF/Word.
- Aislar el motor de OCR detrás de una interfaz reusable para una futura implementación cloud (Textract).
- Hacer configurables `tessdata-path` e `idiomas` sin recompilar.

**Non-Goals:**
- OCR sobre PDFs escaneados (detectar PDF digital vs. escaneado queda para una fase futura).
- Preprocesado de imágenes (deskew, binarización, denoising). Si la imagen es mala, OCR puede devolver texto pobre — eso es aceptable en esta fase.
- Reintentos automáticos al motor de OCR; cualquier excepción se mapea a `TextExtractionException` y termina en `FAILED`.
- Soporte de TIFF u otros formatos: sólo PNG y JPEG en esta entrega.

## Decisions

### Tess4J sobre alternativas
- **Elegido**: `net.sourceforge.tess4j:tess4j` (binding JNA de Tesseract).
- **Alternativas**: invocar `tesseract` por `Runtime.exec` (frágil, dependiente del PATH); usar directamente AWS Textract (introduce dependencia cloud antes de tiempo).
- **Por qué**: Tess4J trae las nativas para Windows/Linux/macOS, es ampliamente usado y se integra como dependencia normal de Maven. La invocación por proceso queda como fallback si Tess4J diera problemas en producción.

### Interfaz `OcrService` aparte del extractor
- `ImageOcrTextExtractor` implementa `DocumentTextExtractor` (pertenece al pipeline) y delega en `OcrService` (pertenece a OCR). Esto permite sustituir el motor (`TesseractOcrService` → `AwsTextractOcrService`) sin tocar el registry ni el resto del dominio.
- Firma propuesta:
  ```java
  public interface OcrService {
      String extractText(InputStream image, String contentType);
  }
  ```
- **Alternativa descartada**: meter Tesseract directamente dentro del extractor. Acopla el dominio al SDK y dificulta el test unitario del extractor (habría que mockear Tess4J, no una interfaz propia).

### Configuración
- Nueva clase `OcrProperties` (`@ConfigurationProperties("app.ocr")`) con:
  - `tessdataPath` (default: `null` → usa el data por defecto que detecte Tess4J).
  - `language` (default: `eng`).
- Valores en `application.yml` y override por entorno (`APP_OCR_TESSDATA_PATH`).

### Manejo de errores
- Cualquier `TesseractException` (o I/O) se envuelve en `TextExtractionException` con mensaje acotado (`"OCR failed"`). El listener de procesamiento ya transiciona a `FAILED` con `errorMessage` seguro.
- `supports(contentType)` devuelve `true` sólo para `image/png` y `image/jpeg` (case-insensitive). Otros formatos siguen cayendo en `UnsupportedDocumentTypeException`.

### Tests
- Unit test del extractor: imagen pequeña embebida en `src/test/resources` con texto conocido ("HELLO OCR"). Aserta substring (OCR no es determinístico carácter a carácter).
- Test de integración (`@SpringBootTest` + Testcontainers ya existentes): subir PNG con texto, esperar transición a `PROCESSED`, verificar `raw_text` no vacío.

## Risks / Trade-offs

- **Tess4J trae binarios nativos pesados** → Mitigación: aceptar el costo en disco; documentar en README que el JAR es más grande. Si se vuelve un problema, se mueve OCR a un sidecar.
- **OCR es lento y bloquea el thread del worker** → Mitigación: el pool async ya está dimensionado para Fase 3; si se vuelve cuello de botella se aumenta `app.async.*` o se mueve a un consumer Kafka dedicado en Fase 7.
- **Calidad del texto extraído depende de la imagen** → Mitigación: aceptarlo; la Fase 5 (data extraction) tolerará texto ruidoso usando regex tolerantes. No agregar preprocesado todavía.
- **Disponibilidad de `tessdata` en distintos entornos** → Mitigación: configurable vía properties; si falta el path, fallar con error claro al arrancar el extractor en lugar de explotar en runtime.
- **Tests dependen de salida no determinística de OCR** → Mitigación: usar imagen de alto contraste con texto simple y aserciones por substring, no igualdad exacta.
