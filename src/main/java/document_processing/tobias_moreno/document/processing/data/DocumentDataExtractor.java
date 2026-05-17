package document_processing.tobias_moreno.document.processing.data;

import java.util.Optional;

public interface DocumentDataExtractor {
    Optional<ExtractedDocument> extract(String rawText);
}
