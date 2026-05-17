package document_processing.tobias_moreno.document.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import document_processing.tobias_moreno.document.Document;
import document_processing.tobias_moreno.document.DocumentRepository;
import document_processing.tobias_moreno.document.DocumentStatus;
import document_processing.tobias_moreno.document.processing.data.DocumentDataExtractorRegistry;
import document_processing.tobias_moreno.document.processing.data.DocumentType;
import document_processing.tobias_moreno.document.processing.data.ExtractedDocument;
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
    private DocumentDataExtractorRegistry dataExtractorRegistry;
    private ObjectMapper objectMapper;
    private DocumentProcessingService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        resultRepository = mock(DocumentResultRepository.class);
        objectStorage = mock(ObjectStorage.class);
        registry = mock(TextExtractorRegistry.class);
        dataExtractorRegistry = mock(DocumentDataExtractorRegistry.class);
        objectMapper = new ObjectMapper();
        when(dataExtractorRegistry.classify(anyString(), any(UUID.class))).thenReturn(Optional.empty());
        service = new DocumentProcessingService(documentRepository, resultRepository, objectStorage, registry,
                dataExtractorRegistry, objectMapper);
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
        assertThat(resultCaptor.getValue().getDocumentType()).isEqualTo("UNKNOWN");
        assertThat(resultCaptor.getValue().getExtractedData()).isNull();
        verify(documentRepository).markAsProcessed(eq(id), any(Instant.class));
        verify(documentRepository, never()).markAsFailed(any(), anyString(), any());
    }

    @Test
    void classifiedDocumentPersistsTypeAndJsonPayload() throws Exception {
        UUID id = UUID.randomUUID();
        Document doc = new Document(id, "x.pdf", "application/pdf", 10, "k/" + id, DocumentStatus.UPLOADED);
        when(documentRepository.markAsProcessing(eq(id), any(Instant.class))).thenReturn(1);
        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));
        when(objectStorage.read("k/" + id)).thenReturn(new ByteArrayInputStream("pdf bytes".getBytes()));
        when(registry.extract(eq("application/pdf"), any(InputStream.class))).thenReturn("FACTURA text");
        Object payload = new java.util.LinkedHashMap<String, Object>() {{
            put("invoiceNumber", "00001-00000001");
            put("total", 850000.00);
        }};
        when(dataExtractorRegistry.classify(eq("FACTURA text"), eq(id)))
                .thenReturn(Optional.of(new ExtractedDocument(DocumentType.INVOICE, payload)));

        service.process(id);

        ArgumentCaptor<DocumentResult> resultCaptor = ArgumentCaptor.forClass(DocumentResult.class);
        verify(resultRepository).save(resultCaptor.capture());
        DocumentResult saved = resultCaptor.getValue();
        assertThat(saved.getDocumentType()).isEqualTo("INVOICE");
        assertThat(saved.getExtractedData()).contains("\"invoiceNumber\":\"00001-00000001\"");
        verify(documentRepository).markAsProcessed(eq(id), any(Instant.class));
    }

    @Test
    void imageHappyPathPersistsOcrText() {
        UUID id = UUID.randomUUID();
        Document doc = new Document(id, "x.png", "image/png", 10, "k/" + id, DocumentStatus.UPLOADED);
        when(documentRepository.markAsProcessing(eq(id), any(Instant.class))).thenReturn(1);
        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));
        when(objectStorage.read("k/" + id)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        when(registry.extract(eq("image/png"), any(InputStream.class))).thenReturn("HELLO OCR");

        service.process(id);

        ArgumentCaptor<DocumentResult> resultCaptor = ArgumentCaptor.forClass(DocumentResult.class);
        verify(resultRepository).save(resultCaptor.capture());
        assertThat(resultCaptor.getValue().getRawText()).isEqualTo("HELLO OCR");
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
    void unsupportedTypeMarksFailedWithGenericMessage() {
        UUID id = UUID.randomUUID();
        Document doc = new Document(id, "x.gif", "image/gif", 10, "k/" + id, DocumentStatus.UPLOADED);
        when(documentRepository.markAsProcessing(eq(id), any(Instant.class))).thenReturn(1);
        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));
        when(objectStorage.read("k/" + id)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        when(registry.extract(eq("image/gif"), any(InputStream.class)))
                .thenThrow(new UnsupportedDocumentTypeException("image/gif"));

        service.process(id);

        verify(documentRepository).markAsFailed(eq(id), eq("Unsupported document type"), any(Instant.class));
        verify(resultRepository, never()).save(any());
    }

    @Test
    void ocrFailureMarksFailedWithGenericMessage() {
        UUID id = UUID.randomUUID();
        Document doc = new Document(id, "x.png", "image/png", 10, "k/" + id, DocumentStatus.UPLOADED);
        when(documentRepository.markAsProcessing(eq(id), any(Instant.class))).thenReturn(1);
        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));
        when(objectStorage.read("k/" + id)).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        when(registry.extract(eq("image/png"), any(InputStream.class)))
                .thenThrow(new TextExtractionException("OCR engine failed"));

        service.process(id);

        verify(documentRepository).markAsFailed(eq(id), eq("Failed to extract text"), any(Instant.class));
        verify(resultRepository, never()).save(any());
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
