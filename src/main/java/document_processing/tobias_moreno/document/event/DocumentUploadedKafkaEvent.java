package document_processing.tobias_moreno.document.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentUploadedKafkaEvent(
        UUID eventId,
        String type,
        UUID documentId,
        Instant occurredAt,
        String correlationId
) {
    public static final String TYPE = "DocumentUploaded";

    public static DocumentUploadedKafkaEvent of(UUID documentId, String correlationId) {
        return new DocumentUploadedKafkaEvent(UUID.randomUUID(), TYPE, documentId, Instant.now(), correlationId);
    }
}
