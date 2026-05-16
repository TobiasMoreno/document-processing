package document_processing.tobias_moreno.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import document_processing.tobias_moreno.config.MinioProperties;
import document_processing.tobias_moreno.support.Fixtures;
import document_processing.tobias_moreno.support.IntegrationTestBase;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentUploadIntegrationTest extends IntegrationTestBase {

    @Autowired WebApplicationContext context;
    @Autowired DocumentRepository repository;
    @Autowired MinioClient minioClient;
    @Autowired MinioProperties minioProperties;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        repository.deleteAll();
        cleanBucket();
    }

    @Test
    void uploadsPdfSuccessfully() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", Fixtures.minimalPdf());

        MvcResult result = mockMvc.perform(multipart("/documents").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentId").exists())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID documentId = UUID.fromString(body.get("documentId").asText());

        Document persisted = repository.findById(documentId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(persisted.getContentType()).isEqualTo("application/pdf");
        assertThat(persisted.getOriginalFilename()).isEqualTo("invoice.pdf");
        assertThat(persisted.getStoragePath()).endsWith(documentId.toString());

        try (InputStream object = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(minioProperties.getBucket())
                        .object(persisted.getStoragePath())
                        .build())) {
            assertThat(object.readAllBytes()).isEqualTo(Fixtures.minimalPdf());
        }
    }

    @Test
    void uploadsDocxSuccessfully() throws Exception {
        byte[] docx = Fixtures.minimalDocx();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "letter.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx);

        mockMvc.perform(multipart("/documents").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADED"));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void uploadsPngSuccessfully() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "pixel.png", "image/png", Fixtures.minimalPng());

        mockMvc.perform(multipart("/documents").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADED"));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/documents").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("EMPTY_FILE"));

        assertNoRowsAndNoObjects();
    }

    @Test
    void rejectsUnsupportedType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "data.txt", "text/plain", "hello world".getBytes());

        mockMvc.perform(multipart("/documents").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CONTENT_TYPE"));

        assertNoRowsAndNoObjects();
    }

    @Test
    void rejectsMismatchedDeclaredAndSniffedType() throws Exception {
        // bytes are PDF but declared as PNG
        MockMultipartFile file = new MockMultipartFile(
                "file", "weird.png", MediaType.IMAGE_PNG_VALUE, Fixtures.minimalPdf());

        mockMvc.perform(multipart("/documents").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CONTENT_TYPE"));

        assertNoRowsAndNoObjects();
    }

    @Test
    void rejectsMissingFilePart() throws Exception {
        mockMvc.perform(multipart("/documents"))
                .andExpect(status().isBadRequest());

        assertNoRowsAndNoObjects();
    }

    @Test
    void errorResponseShapeIsSafe() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        MvcResult result = mockMvc.perform(multipart("/documents").file(file))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("error", "message");
        assertThat(body.get("message").asText())
                .doesNotContain("Exception")
                .doesNotContain("\tat ")
                .doesNotContain("/var/")
                .doesNotContain("C:\\");
    }

    private void assertNoRowsAndNoObjects() throws Exception {
        assertThat(repository.count()).isZero();
        Iterable<Result<Item>> items = minioClient.listObjects(
                ListObjectsArgs.builder().bucket(minioProperties.getBucket()).recursive(true).build());
        assertThat(items.iterator().hasNext()).isFalse();
    }

    private void cleanBucket() {
        try {
            Iterable<Result<Item>> items = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(minioProperties.getBucket()).recursive(true).build());
            for (Result<Item> r : items) {
                String name = r.get().objectName();
                minioClient.removeObject(io.minio.RemoveObjectArgs.builder()
                        .bucket(minioProperties.getBucket())
                        .object(name)
                        .build());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
