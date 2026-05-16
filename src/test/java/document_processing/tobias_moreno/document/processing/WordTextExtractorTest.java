package document_processing.tobias_moreno.document.processing;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class WordTextExtractorTest {

    private final WordTextExtractor extractor = new WordTextExtractor();

    @Test
    void supportsDocAndDocx() {
        assertThat(extractor.supports("application/msword")).isTrue();
        assertThat(extractor.supports("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isTrue();
        assertThat(extractor.supports("application/pdf")).isFalse();
    }

    @Test
    void extractsTextFromDocx() throws Exception {
        byte[] docx = buildDocxContaining("Hello world");

        String text = extractor.extract(new ByteArrayInputStream(docx));

        assertThat(text).contains("Hello world");
    }

    static byte[] buildDocxContaining(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(text);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }
}
