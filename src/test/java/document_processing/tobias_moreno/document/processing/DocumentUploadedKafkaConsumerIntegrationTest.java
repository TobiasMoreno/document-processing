package document_processing.tobias_moreno.document.processing;

import document_processing.tobias_moreno.config.KafkaProperties;
import document_processing.tobias_moreno.document.Document;
import document_processing.tobias_moreno.document.DocumentRepository;
import document_processing.tobias_moreno.document.DocumentStatus;
import document_processing.tobias_moreno.document.event.DocumentUploadedKafkaEvent;
import document_processing.tobias_moreno.document.result.DocumentResultRepository;
import document_processing.tobias_moreno.storage.ObjectStorage;
import document_processing.tobias_moreno.support.IntegrationTestBase;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentUploadedKafkaConsumerIntegrationTest extends IntegrationTestBase {

    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentResultRepository resultRepository;
    @Autowired KafkaTemplate<String, DocumentUploadedKafkaEvent> kafkaTemplate;
    @Autowired KafkaProperties kafkaProperties;
    @Autowired ObjectStorage objectStorage;

    @BeforeEach
    void setup() {
        resultRepository.deleteAll();
        documentRepository.deleteAll();
    }

    @Test
    void consumerProcessesEventEndToEnd() throws Exception {
        UUID documentId = persistUploadedDocument("INVOICE 0001");

        publish(documentId, "corr-end-to-end");

        Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                        .isEqualTo(DocumentStatus.PROCESSED));

        assertThat(resultRepository.findById(documentId)).hasValueSatisfying(r ->
                assertThat(r.getRawText()).contains("INVOICE 0001"));
    }

    @Test
    void redeliveryAfterProcessedIsNoOp() throws Exception {
        UUID documentId = persistUploadedDocument("HELLO");
        publish(documentId, "first");
        Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                        .isEqualTo(DocumentStatus.PROCESSED));
        Instant firstProcessedAt = documentRepository.findById(documentId).orElseThrow().getProcessedAt();
        assertThat(firstProcessedAt).isNotNull();

        publish(documentId, "second");
        Thread.sleep(3000);

        Document doc = documentRepository.findById(documentId).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSED);
        assertThat(doc.getProcessedAt()).isEqualTo(firstProcessedAt);
    }

    private UUID persistUploadedDocument(String pdfText) throws Exception {
        byte[] pdf = PdfTextExtractorTest.buildPdfContaining(pdfText);
        UUID id = UUID.randomUUID();
        String storageKey = "test/" + id;
        objectStorage.store(storageKey, new ByteArrayInputStream(pdf), pdf.length, "application/pdf");
        Document doc = new Document(id, "x.pdf", "application/pdf", pdf.length, storageKey, DocumentStatus.UPLOADED);
        return documentRepository.save(doc).getId();
    }

    private void publish(UUID documentId, String correlationId) {
        DocumentUploadedKafkaEvent event = DocumentUploadedKafkaEvent.of(documentId, correlationId);
        kafkaTemplate.send(kafkaProperties.getTopics().getDocumentUploaded(), documentId.toString(), event);
    }
}
