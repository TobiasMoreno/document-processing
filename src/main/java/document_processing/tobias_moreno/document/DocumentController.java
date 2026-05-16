package document_processing.tobias_moreno.document;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentUploadService uploadService;
    private final DocumentQueryService queryService;

    public DocumentController(DocumentUploadService uploadService, DocumentQueryService queryService) {
        this.uploadService = uploadService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        Document document = uploadService.upload(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(UploadResponse.from(document));
    }

    @GetMapping("/{id}")
    public DocumentResponse getById(@PathVariable("id") UUID id) {
        return DocumentResponse.from(queryService.findById(id));
    }

    @GetMapping("/{id}/status")
    public DocumentStatusResponse getStatus(@PathVariable("id") UUID id) {
        return DocumentStatusResponse.from(queryService.findById(id));
    }
}
