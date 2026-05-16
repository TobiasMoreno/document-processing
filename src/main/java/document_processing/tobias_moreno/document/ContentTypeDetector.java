package document_processing.tobias_moreno.document;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class ContentTypeDetector {

    private final Tika tika = new Tika();

    public String detect(InputStream in, String originalFilename) throws IOException {
        return tika.detect(in, originalFilename);
    }
}
