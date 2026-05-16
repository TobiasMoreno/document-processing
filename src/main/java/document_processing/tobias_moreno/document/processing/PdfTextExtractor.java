package document_processing.tobias_moreno.document.processing;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class PdfTextExtractor implements DocumentTextExtractor {

    private static final String PDF = "application/pdf";

    @Override
    public boolean supports(String contentType) {
        return PDF.equalsIgnoreCase(contentType);
    }

    @Override
    public String extract(InputStream in) {
        try (PDDocument document = Loader.loadPDF(in.readAllBytes())) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new TextExtractionException("Failed to read PDF content", e);
        }
    }
}
