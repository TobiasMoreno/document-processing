package document_processing.tobias_moreno.document.processing;

import document_processing.tobias_moreno.document.processing.ocr.OcrService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TextExtractorRegistryTest {

    @Test
    void dispatchesToPdfExtractor() throws Exception {
        TextExtractorRegistry registry = buildRegistry(mock(OcrService.class));
        byte[] pdf = PdfTextExtractorTest.buildPdfContaining("HELLO");

        String text = registry.extract("application/pdf", new ByteArrayInputStream(pdf));

        assertThat(text).contains("HELLO");
    }

    @Test
    void dispatchesPngToOcrExtractor() {
        OcrService ocr = mock(OcrService.class);
        when(ocr.extractText(any(InputStream.class))).thenReturn("HELLO OCR");
        TextExtractorRegistry registry = buildRegistry(ocr);

        String text = registry.extract("image/png", new ByteArrayInputStream(new byte[]{1, 2, 3}));

        assertThat(text).isEqualTo("HELLO OCR");
    }

    @Test
    void dispatchesJpegToOcrExtractor() {
        OcrService ocr = mock(OcrService.class);
        when(ocr.extractText(any(InputStream.class))).thenReturn("INVOICE 0001");
        TextExtractorRegistry registry = buildRegistry(ocr);

        String text = registry.extract("image/jpeg", new ByteArrayInputStream(new byte[]{1, 2, 3}));

        assertThat(text).isEqualTo("INVOICE 0001");
    }

    @Test
    void throwsUnsupportedForUnknownContentType() {
        TextExtractorRegistry registry = buildRegistry(mock(OcrService.class));

        assertThatThrownBy(() -> registry.extract("image/gif", new ByteArrayInputStream(new byte[]{1, 2, 3})))
                .isInstanceOf(UnsupportedDocumentTypeException.class)
                .extracting("contentType").isEqualTo("image/gif");
    }

    private static TextExtractorRegistry buildRegistry(OcrService ocrService) {
        return new TextExtractorRegistry(List.of(
                new PdfTextExtractor(),
                new WordTextExtractor(),
                new ImageOcrTextExtractor(ocrService)));
    }
}
