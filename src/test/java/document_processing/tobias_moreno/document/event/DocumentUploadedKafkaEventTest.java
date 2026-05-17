package document_processing.tobias_moreno.document.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentUploadedKafkaEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void factoryProducesEnvelopeWithDefaults() {
        UUID documentId = UUID.randomUUID();
        DocumentUploadedKafkaEvent event = DocumentUploadedKafkaEvent.of(documentId, "corr-1");

        assertThat(event.eventId()).isNotNull();
        assertThat(event.type()).isEqualTo("DocumentUploaded");
        assertThat(event.documentId()).isEqualTo(documentId);
        assertThat(event.occurredAt()).isBefore(Instant.now().plusSeconds(1));
        assertThat(event.correlationId()).isEqualTo("corr-1");
    }

    @Test
    void serializesAsExpectedJsonShape() throws Exception {
        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID documentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Instant occurredAt = Instant.parse("2026-05-17T12:00:00Z");
        DocumentUploadedKafkaEvent event = new DocumentUploadedKafkaEvent(
                eventId, "DocumentUploaded", documentId, occurredAt, "corr-x");

        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(node.get("type").asText()).isEqualTo("DocumentUploaded");
        assertThat(node.get("documentId").asText()).isEqualTo(documentId.toString());
        assertThat(node.get("occurredAt").asText()).isEqualTo("2026-05-17T12:00:00Z");
        assertThat(node.get("correlationId").asText()).isEqualTo("corr-x");
    }
}
