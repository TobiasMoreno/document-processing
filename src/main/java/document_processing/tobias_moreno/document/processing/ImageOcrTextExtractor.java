package document_processing.tobias_moreno.document.processing;

import document_processing.tobias_moreno.document.processing.ocr.OcrService;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class ImageOcrTextExtractor implements DocumentTextExtractor {

    private static final String PNG = "image/png";
    private static final String JPEG = "image/jpeg";

    private final OcrService ocrService;

    public ImageOcrTextExtractor(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    @Override
    public boolean supports(String contentType) {
        return PNG.equalsIgnoreCase(contentType) || JPEG.equalsIgnoreCase(contentType);
    }

    @Override
    public String extract(InputStream in) {
        return ocrService.extractText(in);
    }
}
