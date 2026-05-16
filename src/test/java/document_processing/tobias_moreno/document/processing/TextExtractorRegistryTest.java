package document_processing.tobias_moreno.document.processing;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextExtractorRegistryTest {

    private final TextExtractorRegistry registry =
            new TextExtractorRegistry(List.of(new PdfTextExtractor(), new WordTextExtractor()));

    @Test
    void dispatchesToPdfExtractor() throws Exception {
        byte[] pdf = PdfTextExtractorTest.buildPdfContaining("HELLO");

        String text = registry.extract("application/pdf", new ByteArrayInputStream(pdf));

        assertThat(text).contains("HELLO");
    }

    @Test
    void throwsUnsupportedForImagePng() {
        assertThatThrownBy(() -> registry.extract("image/png", new ByteArrayInputStream(new byte[]{1, 2, 3})))
                .isInstanceOf(UnsupportedDocumentTypeException.class)
                .extracting("contentType").isEqualTo("image/png");
    }

    @Test
    void throwsUnsupportedForImageJpeg() {
        assertThatThrownBy(() -> registry.extract("image/jpeg", new ByteArrayInputStream(new byte[]{1, 2, 3})))
                .isInstanceOf(UnsupportedDocumentTypeException.class);
    }
}
