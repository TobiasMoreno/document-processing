package document_processing.tobias_moreno.document;

import java.util.UUID;

public record DocumentStatusResponse(UUID documentId, DocumentStatus status) {

    public static DocumentStatusResponse from(Document document) {
        return new DocumentStatusResponse(document.getId(), document.getStatus());
    }
}
