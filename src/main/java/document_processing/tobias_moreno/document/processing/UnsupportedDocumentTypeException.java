package document_processing.tobias_moreno.document.processing;

public class UnsupportedDocumentTypeException extends TextExtractionException {

    private final String contentType;

    public UnsupportedDocumentTypeException(String contentType) {
        super("No extractor registered for content-type: " + contentType);
        this.contentType = contentType;
    }

    public String getContentType() {
        return contentType;
    }
}
