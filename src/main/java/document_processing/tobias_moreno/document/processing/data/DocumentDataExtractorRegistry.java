package document_processing.tobias_moreno.document.processing.data;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class DocumentDataExtractorRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocumentDataExtractorRegistry.class);

    private final List<DocumentDataExtractor> extractors;
    private final Validator validator;

    public DocumentDataExtractorRegistry(List<DocumentDataExtractor> extractors, Validator validator) {
        this.extractors = extractors;
        this.validator = validator;
    }

    public Optional<ExtractedDocument> classify(String rawText, UUID documentId) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }
        for (DocumentDataExtractor extractor : extractors) {
            String name = extractor.getClass().getSimpleName();
            try {
                Optional<ExtractedDocument> result = extractor.extract(rawText);
                if (result.isEmpty()) {
                    continue;
                }
                ExtractedDocument extracted = result.get();
                Object payload = extracted.data();
                if (payload != null) {
                    Set<ConstraintViolation<Object>> violations = validator.validate(payload);
                    if (!violations.isEmpty()) {
                        String summary = violations.stream()
                                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                                .collect(Collectors.joining(", "));
                        log.warn("Document {} classified as {} but payload failed validation: {}",
                                documentId, extracted.type(), summary);
                        return Optional.empty();
                    }
                }
                return Optional.of(extracted);
            } catch (RuntimeException e) {
                log.warn("Document {} extractor {} threw {}: {}",
                        documentId, name, e.getClass().getSimpleName(), e.getMessage());
            }
        }
        return Optional.empty();
    }
}
