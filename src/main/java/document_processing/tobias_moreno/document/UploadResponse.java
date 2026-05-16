package document_processing.tobias_moreno.document;

import java.util.UUID;

public record UploadResponse(UUID documentId, DocumentStatus status) {

    public static UploadResponse from(Document document) {
        return new UploadResponse(document.getId(), document.getStatus());
    }
}
