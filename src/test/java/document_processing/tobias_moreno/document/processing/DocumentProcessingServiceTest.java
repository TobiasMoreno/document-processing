package document_processing.tobias_moreno.document.processing;

import document_processing.tobias_moreno.document.Document;
import document_processing.tobias_moreno.document.DocumentRepository;
import document_processing.tobias_moreno.document.DocumentStatus;
import document_processing.tobias_moreno.document.result.DocumentResult;
import document_processing.tobias_moreno.document.result.DocumentResultRepository;
import document_processing.tobias_moreno.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentProcessingServiceTest {

    private DocumentRepository documentRepository;
    private DocumentResultRepository resultRepository;
    private ObjectStorage objectStorage;
    private TextExtractorRegistry registry;
    private DocumentProcessingService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        resultRepository = mock(DocumentResultRepository.class);
        objectStorage = mock(ObjectStorage.class);
        registry = mock(TextExtractorRegistry.class);
        service = new DocumentProcessingService(documentRepository, resultRepository, objectStorage, registry);
    }

    @Test
    void happyPathPersistsResultAndMarksProcessed() {
        UUID id = UUID.randomUUID();
        Document doc = new Document(id, "x.pdf", "application/pdf", 10, "k/" + id, DocumentStatus.UPLOADED);
        when(documentRepository.markAsProcessing(eq(id), any(Instant.class))).thenReturn(1);
        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));
        when(objectStorage.read("k/" + id)).thenReturn(new ByteArrayInputStream("pdf bytes".getBytes()));
        when(registry.extract(eq("application/pdf"), any(InputStream.class))).thenReturn("extracted text");

        service.process(id);

        ArgumentCaptor<DocumentResult> resultCaptor = ArgumentCaptor.forClass(DocumentResult.class);
        verify(resultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getRawText()).isEqualTo("extracted text");
        assertThat(resultCaptor.getValue().getDocumentId()).isEqualTo(id);
        verify(documentRepository).markAsProcessed(eq(id), any(Instant.class));
        verify(documentRepository, never()).markAsFailed(any(), anyString(), any());
    }

    @Test
    void casFailureSkipsAllWork() {
        UUID id = UUID.randomUUID();
        when(documentRepository.markAsProcessing(eq(id), any(Instant.class))).thenReturn(0);

        service.process(id);

        verify(documentRepository, never()).findById(any());
        verify(objectStorage, never()).read(anyString());
        verify(registry, never()).extract(anyString(), any());
        verify(resultRepository, never()).save(any());
    }

    @Test
    void unsupportedTypeForPngMarksFailedWithOcrMessage() {
        UUID id = UUID.randomUUID();
        Document doc = new Document(id, "x.png", "image/png", 10, "k/" + id, DocumentStatus.UPLOADED);
        when(documentRepository.markAsProcessing(eq(id), any(Instant.class))).thenReturn(1);
        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));
        when(objectStorage.read("k/" + id)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        when(registry.extract(eq("image/png"), any(InputStream.class)))
                .thenThrow(new UnsupportedDocumentTypeException("image/png"));

        service.process(id);

        verify(documentRepository).markAsFailed(eq(id), eq("OCR not implemented yet"), any(Instant.class));
        verify(resultRepository, never()).save(any());
    }

    @Test
    void unsupportedTypeForJpegMarksFailedWithOcrMessage() {
        UUID id = UUID.randomUUID();
        Document doc = new Document(id, "x.jpg", "image/jpeg", 10, "k/" + id, DocumentStatus.UPLOADED);
        when(documentRepository.markAsProcessing(eq(id), any(Instant.class))).thenReturn(1);
        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));
        when(objectStorage.read("k/" + id)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        when(registry.extract(eq("image/jpeg"), any(InputStream.class)))
                .thenThrow(new UnsupportedDocumentTypeException("image/jpeg"));

        service.process(id);

        verify(documentRepository).markAsFailed(eq(id), eq("OCR not implemented yet"), any(Instant.class));
    }

    @Test
    void textExtractionFailureMarksFailedWithGenericMessage() {
        UUID id = UUID.randomUUID();
        Document doc = new Document(id, "x.pdf", "application/pdf", 10, "k/" + id, DocumentStatus.UPLOADED);
        when(documentRepository.markAsProcessing(eq(id), any(Instant.class))).thenReturn(1);
        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));
        when(objectStorage.read("k/" + id)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        when(registry.extract(anyString(), any(InputStream.class)))
                .thenThrow(new TextExtractionException("PDF parsing died with internal path /tmp/foo"));

        service.process(id);

        verify(documentRepository).markAsFailed(eq(id), eq("Failed to extract text"), any(Instant.class));
        verify(resultRepository, never()).save(any());
    }
}
