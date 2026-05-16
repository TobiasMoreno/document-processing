package document_processing.tobias_moreno.document;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DocumentQueryService {

    private final DocumentRepository repository;

    public DocumentQueryService(DocumentRepository repository) {
        this.repository = repository;
    }

    public Document findById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
    }
}
