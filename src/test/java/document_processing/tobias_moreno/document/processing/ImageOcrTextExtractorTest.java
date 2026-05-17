package document_processing.tobias_moreno.document.processing;

import document_processing.tobias_moreno.document.processing.ocr.OcrService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImageOcrTextExtractorTest {

    @Test
    void supportsOnlyPngAndJpegCaseInsensitive() {
        ImageOcrTextExtractor extractor = new ImageOcrTextExtractor(mock(OcrService.class));

        assertThat(extractor.supports("image/png")).isTrue();
        assertThat(extractor.supports("image/jpeg")).isTrue();
        assertThat(extractor.supports("IMAGE/PNG")).isTrue();
        assertThat(extractor.supports("Image/Jpeg")).isTrue();
    }

    @Test
    void supportsRejectsOtherContentTypes() {
        ImageOcrTextExtractor extractor = new ImageOcrTextExtractor(mock(OcrService.class));

        assertThat(extractor.supports("application/pdf")).isFalse();
        assertThat(extractor.supports("image/gif")).isFalse();
        assertThat(extractor.supports("image/tiff")).isFalse();
        assertThat(extractor.supports("text/plain")).isFalse();
        assertThat(extractor.supports(null)).isFalse();
    }

    @Test
    void extractDelegatesToOcrServiceAndReturnsText() {
        OcrService ocr = mock(OcrService.class);
        when(ocr.extractText(any(InputStream.class))).thenReturn("HELLO OCR");
        ImageOcrTextExtractor extractor = new ImageOcrTextExtractor(ocr);

        String text = extractor.extract(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        assertThat(text).isEqualTo("HELLO OCR");
    }

    @Test
    void extractPropagatesTextExtractionException() {
        OcrService ocr = mock(OcrService.class);
        when(ocr.extractText(any(InputStream.class)))
                .thenThrow(new TextExtractionException("OCR engine failed"));
        ImageOcrTextExtractor extractor = new ImageOcrTextExtractor(ocr);

        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(new byte[]{1})))
                .isInstanceOf(TextExtractionException.class);
    }
}
