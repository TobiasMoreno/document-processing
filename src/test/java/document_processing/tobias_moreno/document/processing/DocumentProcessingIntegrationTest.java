package document_processing.tobias_moreno.document.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import document_processing.tobias_moreno.document.DocumentRepository;
import document_processing.tobias_moreno.document.DocumentStatus;
import document_processing.tobias_moreno.document.result.DocumentResultRepository;
import document_processing.tobias_moreno.support.Fixtures;
import document_processing.tobias_moreno.support.IntegrationTestBase;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentProcessingIntegrationTest extends IntegrationTestBase {

    @Autowired WebApplicationContext context;
    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentResultRepository resultRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        resultRepository.deleteAll();
        documentRepository.deleteAll();
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
            assertThat(r.getDocumentType()).isNull();
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
    void pngUploadEventuallyFailsWithOcrMessage() throws Exception {
        UUID id = uploadFile("pixel.png", "image/png", Fixtures.minimalPng());

        awaitStatus(id, DocumentStatus.FAILED);

        var document = documentRepository.findById(id).orElseThrow();
        assertThat(document.getErrorMessage()).isEqualTo("OCR not implemented yet");
        assertThat(document.getProcessedAt()).isNotNull();
        assertThat(resultRepository.findById(id)).isEmpty();
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
