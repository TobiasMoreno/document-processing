package document_processing.tobias_moreno.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import document_processing.tobias_moreno.support.Fixtures;
import document_processing.tobias_moreno.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentQueryIntegrationTest extends IntegrationTestBase {

    @Autowired WebApplicationContext context;
    @Autowired DocumentRepository repository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        repository.deleteAll();
    }

    private UUID uploadOne() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", Fixtures.minimalPdf());
        MvcResult result = mockMvc.perform(multipart("/documents").file(file))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("documentId").asText());
    }

    @Test
    void returnsMetadataForExistingDocument() throws Exception {
        UUID id = uploadOne();

        MvcResult result = mockMvc.perform(get("/documents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(id.toString()))
                .andExpect(jsonPath("$.originalFilename").value("invoice.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(Fixtures.minimalPdf().length))
                // status may have already transitioned via async processing — assert presence only
                .andExpect(jsonPath("$.status").exists())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("storagePath")).isFalse();
    }

    @Test
    void returnsStatusForExistingDocument() throws Exception {
        UUID id = uploadOne();

        MvcResult result = mockMvc.perform(get("/documents/" + id + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(id.toString()))
                .andExpect(jsonPath("$.status").exists())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("documentId", "status");
    }

    @Test
    void returns404ForUnknownIdOnMetadata() throws Exception {
        mockMvc.perform(get("/documents/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DOCUMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void returns404ForUnknownIdOnStatus() throws Exception {
        mockMvc.perform(get("/documents/" + UUID.randomUUID() + "/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void returns400ForMalformedIdOnMetadata() throws Exception {
        mockMvc.perform(get("/documents/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_DOCUMENT_ID"));
    }

    @Test
    void returns400ForMalformedIdOnStatus() throws Exception {
        mockMvc.perform(get("/documents/abc/status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_DOCUMENT_ID"));
    }

    @Test
    void repeatedQueriesReturnSameBody() throws Exception {
        UUID id = uploadOne();
        // Wait for async processing to settle so the two reads observe the same state.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                mockMvc.perform(get("/documents/" + id + "/status"))
                        .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.is("PROCESSED"),
                                org.hamcrest.Matchers.is("FAILED")))));

        String first = mockMvc.perform(get("/documents/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/documents/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(first).isEqualTo(second);
    }
}
