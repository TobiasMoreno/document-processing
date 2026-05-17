package document_processing.tobias_moreno.document.processing;

import document_processing.tobias_moreno.document.event.DocumentUploadedKafkaEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DocumentUploadedKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadedKafkaConsumer.class);
    private static final String MDC_KEY = "correlationId";

    private final DocumentProcessingService processingService;

    public DocumentUploadedKafkaConsumer(DocumentProcessingService processingService) {
        this.processingService = processingService;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.document-uploaded}",
            groupId = "${app.kafka.consumer.group-id}",
            containerFactory = "documentEventListenerContainerFactory")
    public void onMessage(DocumentUploadedKafkaEvent event) {
        if (event == null || event.documentId() == null) {
            log.warn("Skipping DocumentUploaded message with missing payload: {}", event);
            return;
        }
        String correlationId = event.correlationId() != null ? event.correlationId() : "unknown";
        MDC.put(MDC_KEY, correlationId);
        try {
            processingService.process(event.documentId());
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
