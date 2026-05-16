package document_processing.tobias_moreno.document;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentResponseTest {

    @Test
    void doesNotExposeStoragePath() {
        String[] componentNames = Arrays.stream(DocumentResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);

        assertThat(componentNames).doesNotContain("storagePath");
    }

    @Test
    void mapsAllVisibleFieldsFromEntity() {
        UUID id = UUID.randomUUID();
        Document doc = new Document(id, "invoice.pdf", "application/pdf", 1234L,
                "2026/05/16/" + id, DocumentStatus.UPLOADED);

        DocumentResponse response = DocumentResponse.from(doc);

        assertThat(response.documentId()).isEqualTo(id);
        assertThat(response.originalFilename()).isEqualTo("invoice.pdf");
        assertThat(response.contentType()).isEqualTo("application/pdf");
        assertThat(response.sizeBytes()).isEqualTo(1234L);
        assertThat(response.status()).isEqualTo(DocumentStatus.UPLOADED);
    }

    @Test
    void statusResponseHasOnlyIdAndStatus() {
        String[] componentNames = Arrays.stream(DocumentStatusResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);

        assertThat(componentNames).containsExactlyInAnyOrder("documentId", "status");
    }

    @Test
    void statusResponseFactoryProjectsCorrectFields() {
        UUID id = UUID.randomUUID();
        Document doc = new Document(id, "x.pdf", "application/pdf", 1, "k", DocumentStatus.UPLOADED);

        DocumentStatusResponse response = DocumentStatusResponse.from(doc);

        assertThat(response.documentId()).isEqualTo(id);
        assertThat(response.status()).isEqualTo(DocumentStatus.UPLOADED);
    }

}
