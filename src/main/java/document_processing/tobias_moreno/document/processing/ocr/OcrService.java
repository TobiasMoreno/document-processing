package document_processing.tobias_moreno.document.processing.ocr;

import java.io.InputStream;

public interface OcrService {

    String extractText(InputStream image);
}
