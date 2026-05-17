## Context

Hoy `DocumentProcessingService` extrae `rawText` mediante el `TextExtractorRegistry` y persiste un `DocumentResult` con `documentType=NULL` y `extractedData=NULL`. La Fase 5 del planning pide convertir ese texto en datos estructurados. El primer formato real a soportar es **Factura C argentina (AFIP, cód. 011)** — ver `02-2026.pdf` en la raíz del repo como fixture.

El extractor de texto vía PDFBox produce un layout no canónico: las etiquetas y sus valores no siempre quedan en la misma línea (ver el `rawText` del PDF de ejemplo, donde "Fecha de Emisión:" aparece en una línea y `25/02/2026` aparece varias líneas después junto con otras fechas). La estrategia de extracción tiene que tolerar ese desorden.

## Goals / Non-Goals

**Goals:**
- Definir una abstracción `DocumentDataExtractor` análoga a `DocumentTextExtractor` para que el día de mañana se puedan agregar `ReceiptDataExtractor`, `ContractDataExtractor`, etc., sin tocar `DocumentProcessingService`.
- Implementar `InvoiceDataExtractor` que reconozca Factura C AR y devuelva un `InvoiceData` validado.
- Garantizar que la falla de extracción estructurada **no degrada** el estado del documento (sigue siendo `PROCESSED` con `rawText`).
- Persistir `documentType` y `extractedData` (JSON) en la tabla `document_result` ya existente.

**Non-Goals:**
- No clasificar otros tipos de documento (recibos, contratos, notas de crédito) — quedan para iteraciones futuras.
- No exponer los datos extraídos vía endpoint HTTP — `GET /documents/{id}` y `/result` no cambian en esta change.
- No usar modelos de IA / LLM. Solo regex + parseo determinista.
- No introducir una columna JSONB ni cambiar el schema de DB. Reutilizar la columna `extracted_data` como `text` con JSON dentro.
- No implementar OCR-aware extraction. Asumimos que el `rawText` proviene de PDF/Word/imagen ya extraído por Fase 3-4.

## Decisions

### 1. Abstracción `DocumentDataExtractor` con clasificación dentro
```java
public interface DocumentDataExtractor {
    Optional<ExtractedDocument> extract(String rawText);
}

public record ExtractedDocument(DocumentType type, Object data) {}
```
- El extractor decide internamente si "le pertenece" el texto. Devuelve `Optional.empty()` si no matchea.
- Se descarta un `supports(rawText)` separado: la decisión de soporte y la extracción comparten regex y patrones, separarlas duplica trabajo.

**Alternativa considerada:** método `supports` separado (como `DocumentTextExtractor`). Rechazado porque el contentType es un identificador barato y estable, mientras que detectar "esto es una factura" requiere ya tocar regex pesados — separarlo lleva a hacerlo dos veces.

### 2. Registry con orden determinista
`DocumentDataExtractorRegistry` recibe `List<DocumentDataExtractor>` por inyección Spring y los recorre en orden. El primero que devuelva `Optional.present` gana. Si ninguno matchea → `documentType=UNKNOWN`.

Orden actual: solo `InvoiceDataExtractor`. Futuros extractores se anotan con `@Order` si hace falta.

### 3. Estrategia de matcheo para Factura C
- **Trigger**: contiene literal `"FACTURA"` (case-insensitive) Y algún CUIT válido (regex `\b\d{11}\b` con validación de dígito verificador AR) Y un `Importe Total` parseable.
- **Campos parseados con regex etiquetadas** (label + lookahead a una fecha/número):
  - `Fecha de Emisión:\s*(\d{2}/\d{2}/\d{4})` → `LocalDate`
  - `Fecha de Vto\. para el pago:\s*(\d{2}/\d{2}/\d{4})` (puede estar lejos de la etiqueta — usar `(?s)` y buscar la primera fecha después del label en el texto)
  - `Punto de Venta:\s*(\d{4,5})` y `Comp\. Nro:\s*(\d{8})` → concat con `-`
  - `CUIT:\s*(\d{11})` aparece dos veces (emisor y cliente). Tomar la **primera ocurrencia** como `issuerCuit`, la **segunda** como `customerCuit`.
  - `Razón Social:\s*([^\r\n]+)` primera ocurrencia → `issuerName`
  - `Apellido y Nombre / Razón Social:\s*([^\r\n]+)` → `customerName`
  - `Subtotal:\s*\$?\s*([\d.,]+)` y `Importe Total:\s*\$?\s*([\d.,]+)` → BigDecimal (formato AR: punto miles, coma decimal — implementar `parseArAmount(String)` helper).
  - `CAE N°:\s*(\d{14})`

**Trade-off:** las regex están acopladas al layout del template AFIP actual. Si AFIP cambia el rendering o el documento viene escaneado con OCR (orden de tokens distinto), pueden romperse. Asumido aceptable para esta iteración — los tests con `02-2026.pdf` actúan como contract test.

### 4. Falla blanda en clasificación
Si `InvoiceDataExtractor.extract` lanza una excepción no controlada o el DTO resultante falla la validación Bean Validation, el `DocumentDataExtractorRegistry` lo **captura, loguea warning con `documentId` + nombre del extractor + razón**, y trata el caso como "no matcheó". Resultado final: `documentType=UNKNOWN`, `extractedData=NULL`, status sigue avanzando a `PROCESSED`.

**Por qué:** la promesa core del pipeline en Fase 4 es entregar `rawText`. La extracción estructurada es valor agregado. Romperla y marcar `FAILED` regresionaría el contrato anterior.

### 5. Persistencia: JSON como string en `extracted_data` (text)
Jackson serializa el DTO a `String` y se guarda tal cual. No usamos `jsonb` ni un converter JPA dedicado.

**Alternativa considerada:** columna `jsonb` + `@Type(JsonBinaryType.class)` (Hypersistence Utils). Rechazado por ahora — la query estructurada sobre los campos no es requisito de Fase 5. Cuando aparezca búsqueda por `invoiceNumber`/`issuerCuit` se migrará la columna y se agregará un converter.

### 6. Enum `DocumentType` con dos valores
```java
public enum DocumentType { INVOICE, UNKNOWN }
```
- Persistido como `String` (no `@Enumerated(EnumType.ORDINAL)`) para que ordenar/agregar valores no rompa filas existentes.
- `UNKNOWN` es explícito y se persiste — distinto de `NULL`, que indica "Fase 5 aún no corrió". Esto deja una señal clara para reprocesamiento si se mejora la lógica.

### 7. Validación Bean Validation en `InvoiceData`
- `@NotBlank` en `invoiceNumber`, `issuerCuit`, `customerCuit`, `cae`.
- `@PastOrPresent` en `issueDate` (con tolerancia para test fixtures que pueden ser futuras — usar `@Past` solo si no rompe el fixture del 02-2026.pdf — **decisión: omitir `@PastOrPresent` para el `dueDate`**, las fechas de vencimiento pueden ser futuras).
- `@NotNull` + `@DecimalMin("0.0")` en `total` y `subtotal`.
- `@Pattern` para CUIT (`\d{11}`) y CAE (`\d{14}`).
- Si la validación falla → tratado como "no matchea" (decisión 4).

## Risks / Trade-offs

- **[Regex frágil ante variación de layout AFIP]** → Mitigación: tests con fixture real (`02-2026.pdf`); cuando se rompa, agregar fixture nuevo + ajustar regex. Esto es deuda aceptable hasta que tengamos volumen real.
- **[Falla blanda esconde bugs]** → Mitigación: log warning explícito con razón; métrica futura `invoice_extraction_failures_total` (Fase 9 - observabilidad).
- **[CUIT validation pesada]** → El cálculo del dígito verificador AR es trivial (~10 líneas). Trade-off mínimo, ganancia: filtra strings que parecen CUIT pero no lo son.
- **[Falta `currency` real]** → Hardcodeamos `ARS`. Las Facturas C son siempre en pesos por definición legal, así que es correcto para este tipo. Cuando agreguemos Factura A/B/E habrá que detectar moneda.
- **[`rawText` de PDFBox tiene orden no canónico]** → Las regex usan `(?s)` (DOTALL) cuando hace falta y aceptan whitespace flexible. Documentado en los comentarios de los patterns.
- **[JSON como string limita queries futuras]** → Aceptado para esta iteración (decisión 5). Migración a `jsonb` es follow-up bien delimitado.
