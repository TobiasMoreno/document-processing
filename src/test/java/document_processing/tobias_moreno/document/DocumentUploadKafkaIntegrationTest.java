package document_processing.tobias_moreno.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import document_processing.tobias_moreno.config.KafkaProperties;
import document_processing.tobias_moreno.support.IntegrationTestBase;
import document_processing.tobias_moreno.web.CorrelationIdFilter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentUploadKafkaIntegrationTest extends IntegrationTestBase {

    private static final byte[] PDF = ("%PDF-1.4\n%minimal\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF").getBytes();

    @Autowired WebApplicationContext context;
    @Autowired DocumentRepository documentRepository;
    @Autowired KafkaProperties kafkaProperties;
    @Autowired CorrelationIdFilter correlationIdFilter;

    private MockMvc mockMvc;
    private KafkaConsumer<String, String> consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(correlationIdFilter).build();
        documentRepository.deleteAll();

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(kafkaProperties.getTopics().getDocumentUploaded()));
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void uploadPublishesEnvelopeWithExpectedFields() throws Exception {
        UUID id = upload(null);

        ConsumerRecord<String, String> record = pollForDocument(id);

        JsonNode payload = objectMapper.readTree(record.value());
        assertThat(record.key()).isEqualTo(id.toString());
        assertThat(payload.get("eventId").asText()).isNotBlank();
        assertThat(payload.get("type").asText()).isEqualTo("DocumentUploaded");
        assertThat(payload.get("documentId").asText()).isEqualTo(id.toString());
        assertThat(Instant.parse(payload.get("occurredAt").asText())).isBefore(Instant.now().plusSeconds(2));
        assertThat(payload.get("correlationId").asText()).isNotBlank();
    }

    @Test
    void callerCorrelationIdIsPropagated() throws Exception {
        UUID id = upload("test-corr-42");

        ConsumerRecord<String, String> record = pollForDocument(id);

        JsonNode payload = objectMapper.readTree(record.value());
        assertThat(payload.get("documentId").asText()).isEqualTo(id.toString());
        assertThat(payload.get("correlationId").asText()).isEqualTo("test-corr-42");
    }

    @Test
    void twoUploadsProduceDistinctEvents() throws Exception {
        UUID first = upload(null);
        UUID second = upload(null);

        long deadline = System.currentTimeMillis() + 15_000;
        Map<UUID, JsonNode> received = new HashMap<>();
        while (received.size() < 2 && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> r : records) {
                UUID documentId = UUID.fromString(r.key());
                if (documentId.equals(first) || documentId.equals(second)) {
                    received.put(documentId, objectMapper.readTree(r.value()));
                }
            }
        }
        assertThat(received.keySet()).containsExactlyInAnyOrder(first, second);
        assertThat(received.get(first).get("eventId").asText())
                .isNotEqualTo(received.get(second).get("eventId").asText());
    }

    private UUID upload(String correlationId) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", PDF);
        var request = multipart("/documents").file(file);
        if (correlationId != null) {
            request = request.header("X-Correlation-Id", correlationId);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("documentId").asText());
    }

    private ConsumerRecord<String, String> pollForDocument(UUID documentId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> r : records) {
                if (documentId.toString().equals(r.key())) {
                    return r;
                }
            }
        }
        throw new AssertionError("No Kafka record for documentId " + documentId + " within timeout");
    }
}
