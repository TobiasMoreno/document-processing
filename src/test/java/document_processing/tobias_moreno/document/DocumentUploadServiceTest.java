package document_processing.tobias_moreno.document;

import document_processing.tobias_moreno.config.UploadProperties;
import document_processing.tobias_moreno.storage.ObjectKeyGenerator;
import document_processing.tobias_moreno.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentUploadServiceTest {

    private static final byte[] PDF_BYTES =
            ("%PDF-1.4\n%minimal\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF").getBytes(StandardCharsets.US_ASCII);

    private ObjectStorage objectStorage;
    private ObjectKeyGenerator keyGenerator;
    private ContentTypeDetector contentTypeDetector;
    private DocumentRepository repository;
    private UploadProperties uploadProperties;
    private DocumentUploadService service;

    @BeforeEach
    void setUp() {
        objectStorage = mock(ObjectStorage.class);
        keyGenerator = new ObjectKeyGenerator(Clock.fixed(Instant.parse("2026-05-16T10:00:00Z"), ZoneOffset.UTC));
        contentTypeDetector = mock(ContentTypeDetector.class);
        repository = mock(DocumentRepository.class);
        uploadProperties = new UploadProperties();
        uploadProperties.setMaxFileSize(10L * 1024 * 1024);
        uploadProperties.setAllowedContentTypes(List.of(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "image/png",
                "image/jpeg"));
        service = new DocumentUploadService(objectStorage, keyGenerator, contentTypeDetector, repository, uploadProperties);
    }

    @Test
    void rejectsEmptyFile() {
        MultipartFile empty = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service.upload(empty)).isInstanceOf(EmptyFileException.class);
        verify(objectStorage, never()).store(anyString(), any(), anyLong(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsFileTooLarge() throws IOException {
        uploadProperties.setMaxFileSize(10);
        MockMultipartFile big = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[100]);
        when(contentTypeDetector.detect(any(), anyString())).thenReturn("application/pdf");

        assertThatThrownBy(() -> service.upload(big)).isInstanceOf(FileTooLargeException.class);
        verify(objectStorage, never()).store(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void rejectsUnsupportedSniffedType() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "x.zip", "application/pdf", "zipdata".getBytes());
        when(contentTypeDetector.detect(any(InputStream.class), eq("x.zip"))).thenReturn("application/zip");

        assertThatThrownBy(() -> service.upload(file)).isInstanceOf(InvalidContentTypeException.class);
        verify(objectStorage, never()).store(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void rejectsMismatchedDeclaredAndSniffedType() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", "irrelevant".getBytes());
        when(contentTypeDetector.detect(any(InputStream.class), eq("x.pdf"))).thenReturn("image/png");

        assertThatThrownBy(() -> service.upload(file)).isInstanceOf(InvalidContentTypeException.class);
        verify(objectStorage, never()).store(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void persistsAndStoresOnHappyPath() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", PDF_BYTES);
        when(contentTypeDetector.detect(any(InputStream.class), eq("invoice.pdf"))).thenReturn("application/pdf");
        when(repository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        Document saved = service.upload(file);

        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(saved.getContentType()).isEqualTo("application/pdf");
        assertThat(saved.getSizeBytes()).isEqualTo(PDF_BYTES.length);
        assertThat(saved.getStoragePath()).startsWith("2026/05/16/").endsWith(saved.getId().toString());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(objectStorage).store(keyCaptor.capture(), any(InputStream.class), eq((long) PDF_BYTES.length), eq("application/pdf"));
        assertThat(keyCaptor.getValue()).isEqualTo(saved.getStoragePath());
    }

    @Test
    void deletesStoredObjectWhenRepositorySaveFails() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", PDF_BYTES);
        when(contentTypeDetector.detect(any(InputStream.class), eq("x.pdf"))).thenReturn("application/pdf");
        doThrow(new RuntimeException("db down")).when(repository).save(any(Document.class));

        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        ArgumentCaptor<String> storeKey = ArgumentCaptor.forClass(String.class);
        verify(objectStorage).store(storeKey.capture(), any(InputStream.class), anyLong(), anyString());
        verify(objectStorage, atLeastOnce()).delete(storeKey.getValue());
    }

    @Test
    void cleanupFailureIsSwallowed() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", PDF_BYTES);
        when(contentTypeDetector.detect(any(InputStream.class), eq("x.pdf"))).thenReturn("application/pdf");
        doThrow(new RuntimeException("db down")).when(repository).save(any(Document.class));
        doThrow(new RuntimeException("minio also down")).when(objectStorage).delete(anyString());

        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");
    }

    @Test
    void acceptsWhenDeclaredTypeIsMissing() throws IOException {
        MultipartFile file = new MockMultipartFile("file", "x.pdf", null, PDF_BYTES);
        when(contentTypeDetector.detect(any(InputStream.class), eq("x.pdf"))).thenReturn("application/pdf");
        when(repository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        Document saved = service.upload(file);

        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.UPLOADED);
    }

}
