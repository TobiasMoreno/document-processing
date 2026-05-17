package document_processing.tobias_moreno.document.processing;

import document_processing.tobias_moreno.document.event.DocumentUploadedKafkaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DocumentUploadedKafkaConsumerTest {

    private DocumentProcessingService processingService;
    private DocumentUploadedKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        processingService = mock(DocumentProcessingService.class);
        consumer = new DocumentUploadedKafkaConsumer(processingService);
        MDC.clear();
    }

    @Test
    void invokesProcessAndPropagatesCorrelationIdToMdc() {
        UUID documentId = UUID.randomUUID();
        DocumentUploadedKafkaEvent event = new DocumentUploadedKafkaEvent(
                UUID.randomUUID(), "DocumentUploaded", documentId, Instant.now(), "corr-xyz");
        AtomicReference<String> mdcDuringCall = new AtomicReference<>();
        doAnswer(inv -> {
            mdcDuringCall.set(MDC.get("correlationId"));
            return null;
        }).when(processingService).process(documentId);

        consumer.onMessage(event);

        verify(processingService).process(documentId);
        assertThat(mdcDuringCall.get()).isEqualTo("corr-xyz");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void falsybackCorrelationIdWhenMissing() {
        UUID documentId = UUID.randomUUID();
        DocumentUploadedKafkaEvent event = new DocumentUploadedKafkaEvent(
                UUID.randomUUID(), "DocumentUploaded", documentId, Instant.now(), null);
        AtomicReference<String> mdcDuringCall = new AtomicReference<>();
        doAnswer(inv -> {
            mdcDuringCall.set(MDC.get("correlationId"));
            return null;
        }).when(processingService).process(documentId);

        consumer.onMessage(event);

        assertThat(mdcDuringCall.get()).isEqualTo("unknown");
    }

    @Test
    void clearsMdcEvenWhenProcessThrows() {
        UUID documentId = UUID.randomUUID();
        DocumentUploadedKafkaEvent event = new DocumentUploadedKafkaEvent(
                UUID.randomUUID(), "DocumentUploaded", documentId, Instant.now(), "corr-fail");
        doAnswer(inv -> { throw new RuntimeException("boom"); }).when(processingService).process(documentId);

        assertThatCode(() -> consumer.onMessage(event)).isInstanceOf(RuntimeException.class);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void skipsNullEvent() {
        consumer.onMessage(null);
        verify(processingService, never()).process(any());
    }

    @Test
    void skipsEventWithoutDocumentId() {
        DocumentUploadedKafkaEvent event = new DocumentUploadedKafkaEvent(
                UUID.randomUUID(), "DocumentUploaded", null, Instant.now(), "corr");
        consumer.onMessage(event);
        verify(processingService, never()).process(any());
    }
}
