package document_processing.tobias_moreno.document;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentQueryServiceTest {

    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final DocumentQueryService service = new DocumentQueryService(repository);

    @Test
    void returnsDocumentWhenPresent() {
        UUID id = UUID.randomUUID();
        Document doc = new Document(id, "x.pdf", "application/pdf", 10, "2026/05/16/" + id, DocumentStatus.UPLOADED);
        when(repository.findById(id)).thenReturn(Optional.of(doc));

        Document result = service.findById(id);

        assertThat(result).isSameAs(doc);
    }

    @Test
    void throwsWhenAbsent() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id))
                .isInstanceOf(DocumentNotFoundException.class);
    }
}
