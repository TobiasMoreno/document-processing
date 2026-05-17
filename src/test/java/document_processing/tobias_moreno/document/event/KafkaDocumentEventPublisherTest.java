package document_processing.tobias_moreno.document.event;

import document_processing.tobias_moreno.config.KafkaProperties;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaDocumentEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, DocumentUploadedKafkaEvent> kafkaTemplate = mock(KafkaTemplate.class);
    private KafkaProperties properties;
    private KafkaDocumentEventPublisher publisher;

    @BeforeEach
    void setUp() {
        properties = new KafkaProperties();
        properties.getTopics().setDocumentUploaded("document.uploaded");
        properties.setPublishTimeoutMs(2000);
        publisher = new KafkaDocumentEventPublisher(kafkaTemplate, properties);
    }

    @Test
    void sendsToConfiguredTopicWithDocumentIdAsKey() {
        UUID documentId = UUID.randomUUID();
        DocumentUploadedKafkaEvent event = DocumentUploadedKafkaEvent.of(documentId, "corr");
        SendResult<String, DocumentUploadedKafkaEvent> sendResult =
                new SendResult<>(null, new RecordMetadata(new TopicPartition("document.uploaded", 0), 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(eq("document.uploaded"), anyString(), any(DocumentUploadedKafkaEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        publisher.publishUploaded(event);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<DocumentUploadedKafkaEvent> valueCaptor = ArgumentCaptor.forClass(DocumentUploadedKafkaEvent.class);
        verify(kafkaTemplate).send(eq("document.uploaded"), keyCaptor.capture(), valueCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo(documentId.toString());
        assertThat(valueCaptor.getValue()).isSameAs(event);
    }

    @Test
    void swallowsExceptionsToKeepUploadFlowAlive() {
        DocumentUploadedKafkaEvent event = DocumentUploadedKafkaEvent.of(UUID.randomUUID(), "corr");
        CompletableFuture<SendResult<String, DocumentUploadedKafkaEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(anyString(), anyString(), any(DocumentUploadedKafkaEvent.class))).thenReturn(failed);

        assertThatCode(() -> publisher.publishUploaded(event)).doesNotThrowAnyException();
    }
}
