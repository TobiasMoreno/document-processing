package document_processing.tobias_moreno.document;

import document_processing.tobias_moreno.config.UploadProperties;
import document_processing.tobias_moreno.storage.ObjectKeyGenerator;
import document_processing.tobias_moreno.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentUploadService {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadService.class);

    private final ObjectStorage objectStorage;
    private final ObjectKeyGenerator keyGenerator;
    private final ContentTypeDetector contentTypeDetector;
    private final DocumentRepository documentRepository;
    private final UploadProperties uploadProperties;

    public DocumentUploadService(ObjectStorage objectStorage,
                                 ObjectKeyGenerator keyGenerator,
                                 ContentTypeDetector contentTypeDetector,
                                 DocumentRepository documentRepository,
                                 UploadProperties uploadProperties) {
        this.objectStorage = objectStorage;
        this.keyGenerator = keyGenerator;
        this.contentTypeDetector = contentTypeDetector;
        this.documentRepository = documentRepository;
        this.uploadProperties = uploadProperties;
    }

    public Document upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new EmptyFileException("Uploaded file is empty");
        }

        long size = file.getSize();
        if (size > uploadProperties.getMaxFileSize()) {
            throw new FileTooLargeException(
                    "File exceeds the maximum allowed size of " + uploadProperties.getMaxFileSize() + " bytes");
        }

        String declaredContentType = file.getContentType();
        String detectedContentType = detectContentType(file);
        Set<String> allowed = uploadProperties.allowedContentTypesAsSet();

        if (!allowed.contains(detectedContentType)) {
            throw new InvalidContentTypeException("Content type '" + detectedContentType + "' is not supported");
        }
        if (declaredContentType != null
                && !declaredContentType.isBlank()
                && !declaredContentType.equalsIgnoreCase(detectedContentType)) {
            throw new InvalidContentTypeException(
                    "Declared content type does not match the file contents");
        }

        UUID documentId = UUID.randomUUID();
        String storageKey = keyGenerator.keyFor(documentId);

        try (InputStream in = file.getInputStream()) {
            objectStorage.store(storageKey, in, size, detectedContentType);
        } catch (IOException e) {
            throw new document_processing.tobias_moreno.storage.StorageException(
                    "Failed to read uploaded file", e);
        }

        Document document = new Document(
                documentId,
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "unnamed",
                detectedContentType,
                size,
                storageKey,
                DocumentStatus.UPLOADED);

        try {
            return documentRepository.save(document);
        } catch (RuntimeException dbError) {
            try {
                objectStorage.delete(storageKey);
            } catch (RuntimeException cleanupError) {
                log.warn("Failed to clean up storage object {} after DB failure: {}",
                        storageKey, cleanupError.getMessage());
            }
            throw dbError;
        }
    }

    private String detectContentType(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return contentTypeDetector.detect(in, file.getOriginalFilename());
        } catch (IOException e) {
            throw new InvalidContentTypeException("Unable to determine content type of uploaded file");
        }
    }
}
