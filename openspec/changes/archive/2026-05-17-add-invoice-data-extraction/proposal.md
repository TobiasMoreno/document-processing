## Why

Fase 4 dejó `rawText` extraído pero `document_result.document_type` y `extracted_data` siguen NULL. El siguiente paso del pipeline es convertir ese texto en datos estructurados utilizables aguas abajo (consumidores Kafka, búsquedas, dashboards). El caso de uso inicial es la **Factura C argentina (AFIP/ARCA, cód. 011)**, que es el formato real que vamos a procesar primero.

## What Changes

- Nueva abstracción `DocumentDataExtractor` (interface) con `supports(rawText)` + `extract(rawText)` → `Optional<ExtractedDocument>`.
- Implementación `InvoiceDataExtractor` basada en regex para Factura C AR. Campos: `invoiceNumber` (formato `PV-CN`), `issueDate`, `dueDate`, `subtotal`, `total`, `currency` (fijo `ARS` esta iteración), `issuerCuit`, `issuerName`, `customerCuit`, `customerName`, `cae`.
- Nuevo `DocumentDataExtractorRegistry` con auto-discovery vía Spring (mismo patrón que `TextExtractorRegistry`).
- DTO `InvoiceData` validado con Bean Validation.
- `DocumentProcessingService` invoca el registry tras extraer texto. Si matchea: `documentType = INVOICE` + `extractedData` (JSON). Si no matchea o falla la validación: `documentType = UNKNOWN`, `extractedData = NULL`. **En ningún caso una falla de extracción estructurada marca el documento como `FAILED`** — el `rawText` ya está y se persiste igual.
- Enum `DocumentType { INVOICE, UNKNOWN }` (Java); columna `document_type` queda como string.
- Persistencia de `extracted_data` como JSON string en la columna ya existente (nullable).

## Capabilities

### New Capabilities
<!-- Ninguna capability nueva: la funcionalidad se agrega como nuevos requirements dentro de document-processing. -->

### Modified Capabilities
- `document-processing`: se agregan requirements para clasificación de tipo de documento y extracción de datos estructurados a partir del `rawText`. Se aclara explícitamente que fallar la extracción estructurada NO transiciona el documento a `FAILED`.

## Impact

- **Código**: nuevos paquetes/clases bajo `document/processing/data/` (extractor interface, registry, `InvoiceDataExtractor`, `InvoiceData` DTO, `DocumentType` enum). Modificación de `DocumentProcessingService` para llamar al registry y persistir los campos. Modificación de `DocumentResult` (mapeo de `documentType` y `extractedData`).
- **Dependencias**: agregar Jackson para serialización del DTO a JSON (probablemente ya transitivo vía Spring Boot) y `spring-boot-starter-validation` (verificar si ya está).
- **Schema DB**: ninguno — las columnas `document_type` y `extracted_data` ya existen nullable desde Fase 3.
- **API HTTP**: no cambia el contrato en esta iteración. Exponer los campos por endpoint es un follow-up separado.
- **Tests**: nueva fixture con el `rawText` del PDF `02-2026.pdf`. Tests unitarios del extractor + integration tests del pipeline completo.
