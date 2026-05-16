package document_processing.tobias_moreno.document.result;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DocumentResultRepository extends JpaRepository<DocumentResult, UUID> {
}
