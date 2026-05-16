package document_processing.tobias_moreno.document.processing;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Component
public class TextExtractorRegistry {

    private final List<DocumentTextExtractor> extractors;

    public TextExtractorRegistry(List<DocumentTextExtractor> extractors) {
        this.extractors = extractors;
    }

    public String extract(String contentType, InputStream in) {
        return extractors.stream()
                .filter(e -> e.supports(contentType))
                .findFirst()
                .orElseThrow(() -> new UnsupportedDocumentTypeException(contentType))
                .extract(in);
    }
}
