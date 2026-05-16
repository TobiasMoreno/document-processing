package document_processing.tobias_moreno.document.event;

import java.util.UUID;

public record DocumentUploadedEvent(UUID documentId) {
}
