package document_processing.tobias_moreno.document;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID documentId,
        String originalFilename,
        String contentType,
        long sizeBytes,
        DocumentStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant processedAt,
        String errorMessage) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getOriginalFilename(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getStatus(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                document.getProcessedAt(),
                document.getErrorMessage());
    }
}
