package document_processing.tobias_moreno.document.processing;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class WordTextExtractor implements DocumentTextExtractor {

    private static final String DOC = "application/msword";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Override
    public boolean supports(String contentType) {
        return DOC.equalsIgnoreCase(contentType) || DOCX.equalsIgnoreCase(contentType);
    }

    @Override
    public String extract(InputStream in) {
        try {
            byte[] bytes = in.readAllBytes();
            if (looksLikeOoxml(bytes)) {
                try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(bytes));
                     XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    return extractor.getText();
                }
            }
            try (HWPFDocument document = new HWPFDocument(new java.io.ByteArrayInputStream(bytes));
                 WordExtractor extractor = new WordExtractor(document)) {
                return extractor.getText();
            }
        } catch (IOException e) {
            throw new TextExtractionException("Failed to read Word content", e);
        }
    }

    // OOXML files (.docx) are ZIPs starting with PK; legacy .doc uses the OLE2 magic 0xD0CF11E0A1B11AE1.
    private static boolean looksLikeOoxml(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 0x50 && bytes[1] == 0x4B
                && bytes[2] == 0x03 && bytes[3] == 0x04;
    }
}
