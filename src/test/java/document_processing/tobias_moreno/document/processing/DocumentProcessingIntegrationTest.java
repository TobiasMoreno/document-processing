package document_processing.tobias_moreno.document.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import document_processing.tobias_moreno.document.DocumentRepository;
import document_processing.tobias_moreno.document.DocumentStatus;
import document_processing.tobias_moreno.document.processing.TextExtractionException;
import document_processing.tobias_moreno.document.processing.ocr.OcrService;
import document_processing.tobias_moreno.document.result.DocumentResultRepository;
import document_processing.tobias_moreno.support.Fixtures;
import document_processing.tobias_moreno.support.IntegrationTestBase;
import document_processing.tobias_moreno.support.StubOcrService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(DocumentProcessingIntegrationTest.OcrTestConfig.class)
class DocumentProcessingIntegrationTest extends IntegrationTestBase {

    @Autowired WebApplicationContext context;
    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentResultRepository resultRepository;
    @Autowired StubOcrService ocrService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        resultRepository.deleteAll();
        documentRepository.deleteAll();
        ocrService.reset();
    }

    @TestConfiguration
    static class OcrTestConfig {
        @Bean
        @Primary
        StubOcrService stubOcrService() {
            return new StubOcrService();
        }
    }

    private UUID uploadFile(String filename, String contentType, byte[] bytes) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, contentType, bytes);
        MvcResult result = mockMvc.perform(multipart("/documents").file(file))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("documentId").asText());
    }

    private void awaitStatus(UUID id, DocumentStatus expected) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(documentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(expected));
    }

    @Test
    void pdfUploadReachesProcessedWithExtractedText() throws Exception {
        byte[] pdf = PdfTextExtractorTest.buildPdfContaining("INVOICE 0001");
        UUID id = uploadFile("invoice.pdf", "application/pdf", pdf);

        awaitStatus(id, DocumentStatus.PROCESSED);

        assertThat(documentRepository.findById(id).orElseThrow().getProcessedAt()).isNotNull();
        assertThat(resultRepository.findById(id)).hasValueSatisfying(r -> {
            assertThat(r.getRawText()).contains("INVOICE 0001");
            assertThat(r.getDocumentType()).isEqualTo("UNKNOWN");
            assertThat(r.getExtractedData()).isNull();
        });
    }

    @Test
    void docxUploadReachesProcessedWithExtractedText() throws Exception {
        byte[] docx = WordTextExtractorTest.buildDocxContaining("Hello world");
        UUID id = uploadFile("letter.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);

        awaitStatus(id, DocumentStatus.PROCESSED);

        assertThat(resultRepository.findById(id)).hasValueSatisfying(r ->
                assertThat(r.getRawText()).contains("Hello world"));
    }

    @Test
    void pngUploadReachesProcessedWithOcrText() throws Exception {
        ocrService.respondWith("HELLO OCR");
        UUID id = uploadFile("pixel.png", "image/png", Fixtures.minimalPng());

        awaitStatus(id, DocumentStatus.PROCESSED);

        assertThat(documentRepository.findById(id).orElseThrow().getProcessedAt()).isNotNull();
        assertThat(resultRepository.findById(id)).hasValueSatisfying(r ->
                assertThat(r.getRawText()).contains("HELLO OCR"));
    }

    @Test
    void pngUploadFailsWithSafeMessageWhenOcrThrows() throws Exception {
        ocrService.failWith(new TextExtractionException("internal: tessdata at /tmp/foo missing"));
        UUID id = uploadFile("pixel.png", "image/png", Fixtures.minimalPng());

        awaitStatus(id, DocumentStatus.FAILED);

        var document = documentRepository.findById(id).orElseThrow();
        assertThat(document.getErrorMessage()).isEqualTo("Failed to extract text");
        assertThat(document.getProcessedAt()).isNotNull();
        assertThat(resultRepository.findById(id)).isEmpty();
    }

    @Test
    void facturaCPdfIsClassifiedAsInvoiceWithStructuredData() throws Exception {
        byte[] pdf;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/factura-c.pdf")) {
            assertThat(in).isNotNull();
            pdf = in.readAllBytes();
        }
        UUID id = uploadFile("factura.pdf", "application/pdf", pdf);

        awaitStatus(id, DocumentStatus.PROCESSED);

        assertThat(resultRepository.findById(id)).hasValueSatisfying(r -> {
            assertThat(r.getDocumentType()).isEqualTo("INVOICE");
            assertThat(r.getExtractedData()).isNotNull();
            JsonNode payload;
            try {
                payload = objectMapper.readTree(r.getExtractedData());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            assertThat(payload.get("invoiceNumber").asText()).isEqualTo("00001-00000001");
            assertThat(payload.get("issueDate").asText()).isEqualTo("2026-02-25");
            assertThat(payload.get("total").decimalValue()).isEqualByComparingTo("850000.00");
            assertThat(payload.get("currency").asText()).isEqualTo("ARS");
            assertThat(payload.get("issuerCuit").asText()).isEqualTo("20428563787");
            assertThat(payload.get("customerCuit").asText()).isEqualTo("20111111112");
            assertThat(payload.get("cae").asText()).isEqualTo("86096124599717");
        });
    }

    @Test
    void getDocumentResponseShapeIsUnchanged() throws Exception {
        byte[] pdf = PdfTextExtractorTest.buildPdfContaining("X");
        UUID id = uploadFile("x.pdf", "application/pdf", pdf);

        awaitStatus(id, DocumentStatus.PROCESSED);

        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/documents/" + id))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(body.has("rawText")).isFalse();
        assertThat(body.has("extractedData")).isFalse();
        assertThat(body.has("storagePath")).isFalse();
    }
}
