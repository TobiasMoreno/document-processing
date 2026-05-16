package document_processing.tobias_moreno.document.processing;

import document_processing.tobias_moreno.document.Document;
import document_processing.tobias_moreno.document.DocumentRepository;
import document_processing.tobias_moreno.document.result.DocumentResult;
import document_processing.tobias_moreno.document.result.DocumentResultRepository;
import document_processing.tobias_moreno.storage.ObjectStorage;
import document_processing.tobias_moreno.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1024;

    private final DocumentRepository documentRepository;
    private final DocumentResultRepository resultRepository;
    private final ObjectStorage objectStorage;
    private final TextExtractorRegistry registry;

    public DocumentProcessingService(DocumentRepository documentRepository,
                                     DocumentResultRepository resultRepository,
                                     ObjectStorage objectStorage,
                                     TextExtractorRegistry registry) {
        this.documentRepository = documentRepository;
        this.resultRepository = resultRepository;
        this.objectStorage = objectStorage;
        this.registry = registry;
    }

    public void process(UUID documentId) {
        Instant now = Instant.now();
        int updated = documentRepository.markAsProcessing(documentId, now);
        if (updated == 0) {
            log.debug("Skipping {}: not in UPLOADED state", documentId);
            return;
        }

        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            log.warn("Document {} disappeared after CAS to PROCESSING", documentId);
            return;
        }

        try (InputStream in = objectStorage.read(document.getStoragePath())) {
            String text = registry.extract(document.getContentType(), in);
            resultRepository.save(new DocumentResult(documentId, text));
            documentRepository.markAsProcessed(documentId, Instant.now());
            log.info("Processed document {}", documentId);
        } catch (UnsupportedDocumentTypeException e) {
            String reason = isImageContentType(e.getContentType())
                    ? "OCR not implemented yet"
                    : "Unsupported document type";
            failDocument(documentId, reason);
        } catch (TextExtractionException e) {
            log.error("Text extraction failed for document {}", documentId, e);
            failDocument(documentId, "Failed to extract text");
        } catch (StorageException e) {
            log.error("Storage read failed for document {}", documentId, e);
            failDocument(documentId, "Failed to read document from storage");
        } catch (IOException e) {
            log.error("I/O error processing document {}", documentId, e);
            failDocument(documentId, "Processing failed");
        } catch (RuntimeException e) {
            log.error("Unexpected error processing document {}", documentId, e);
            failDocument(documentId, "Processing failed");
        }
    }

    private void failDocument(UUID documentId, String reason) {
        String safe = reason.length() > MAX_ERROR_MESSAGE_LENGTH
                ? reason.substring(0, MAX_ERROR_MESSAGE_LENGTH)
                : reason;
        documentRepository.markAsFailed(documentId, safe, Instant.now());
    }

    private static boolean isImageContentType(String contentType) {
        return contentType != null
                && (contentType.equalsIgnoreCase("image/png") || contentType.equalsIgnoreCase("image/jpeg"));
    }
}
