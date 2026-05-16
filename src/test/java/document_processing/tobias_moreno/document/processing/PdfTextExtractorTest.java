package document_processing.tobias_moreno.document.processing;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void extractsTextFromPdfWithKnownContent() throws Exception {
        byte[] pdf = buildPdfContaining("INVOICE 0001");

        String text = extractor.extract(new ByteArrayInputStream(pdf));

        assertThat(text).contains("INVOICE 0001");
    }

    @Test
    void supportsApplicationPdf() {
        assertThat(extractor.supports("application/pdf")).isTrue();
        assertThat(extractor.supports("APPLICATION/PDF")).isTrue();
        assertThat(extractor.supports("image/png")).isFalse();
    }

    @Test
    void corruptedBytesRaiseTextExtractionException() {
        byte[] junk = "not a pdf at all".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(junk)))
                .isInstanceOf(TextExtractionException.class);
    }

    static byte[] buildPdfContaining(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(100, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
