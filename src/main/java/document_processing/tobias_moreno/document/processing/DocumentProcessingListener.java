package document_processing.tobias_moreno.document.processing;

import document_processing.tobias_moreno.document.event.DocumentUploadedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class DocumentProcessingListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingListener.class);

    private final DocumentProcessingService processingService;

    public DocumentProcessingListener(DocumentProcessingService processingService) {
        this.processingService = processingService;
    }

    @Async("documentProcessingExecutor")
    @EventListener
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        try {
            processingService.process(event.documentId());
        } catch (Throwable t) {
            log.error("Listener swallowed unexpected error for document {}", event.documentId(), t);
        }
    }
}
