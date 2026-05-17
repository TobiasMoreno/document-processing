package document_processing.tobias_moreno.document.event;

public interface DocumentEventPublisher {
    void publishUploaded(DocumentUploadedKafkaEvent event);
}
