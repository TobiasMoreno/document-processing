package document_processing.tobias_moreno.document.event;

import document_processing.tobias_moreno.config.KafkaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaDocumentEventPublisher implements DocumentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaDocumentEventPublisher.class);

    private final KafkaTemplate<String, DocumentUploadedKafkaEvent> kafkaTemplate;
    private final KafkaProperties properties;

    public KafkaDocumentEventPublisher(KafkaTemplate<String, DocumentUploadedKafkaEvent> kafkaTemplate,
                                       KafkaProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void publishUploaded(DocumentUploadedKafkaEvent event) {
        String topic = properties.getTopics().getDocumentUploaded();
        String key = event.documentId().toString();
        try {
            kafkaTemplate.send(topic, key, event)
                    .get(properties.getPublishTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while publishing DocumentUploaded for document {} (correlationId={})",
                    event.documentId(), event.correlationId());
        } catch (TimeoutException e) {
            log.error("Timeout publishing DocumentUploaded for document {} (correlationId={}): {}",
                    event.documentId(), event.correlationId(), e.getMessage());
        } catch (RuntimeException | java.util.concurrent.ExecutionException e) {
            log.error("Failed to publish DocumentUploaded for document {} (correlationId={}): {}",
                    event.documentId(), event.correlationId(), e.getMessage());
        }
    }
}
