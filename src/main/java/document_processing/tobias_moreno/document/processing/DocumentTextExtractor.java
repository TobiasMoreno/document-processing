package document_processing.tobias_moreno.document.processing;

import java.io.InputStream;

public interface DocumentTextExtractor {

    boolean supports(String contentType);

    String extract(InputStream in);
}
