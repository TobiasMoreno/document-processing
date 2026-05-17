package document_processing.tobias_moreno.support;

import document_processing.tobias_moreno.document.processing.TextExtractionException;
import document_processing.tobias_moreno.document.processing.ocr.OcrService;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test double for the OCR engine. Integration tests run without Tesseract installed,
 * so we replace {@code OcrService} with this stub and drive its behavior per-test.
 */
public class StubOcrService implements OcrService {

    private final AtomicReference<String> nextText = new AtomicReference<>("STUB OCR TEXT");
    private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();

    public void respondWith(String text) {
        nextText.set(text);
        nextFailure.set(null);
    }

    public void failWith(RuntimeException error) {
        nextFailure.set(error);
    }

    public void reset() {
        nextText.set("STUB OCR TEXT");
        nextFailure.set(null);
    }

    @Override
    public String extractText(InputStream image) {
        RuntimeException failure = nextFailure.get();
        if (failure != null) {
            if (failure instanceof TextExtractionException tex) {
                throw new TextExtractionException(tex.getMessage());
            }
            throw failure;
        }
        try {
            image.readAllBytes();
        } catch (Exception ignored) {
            // tests don't care if the stream was drained
        }
        return nextText.get();
    }
}
