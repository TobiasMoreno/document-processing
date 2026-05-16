package document_processing.tobias_moreno.storage;

import java.io.InputStream;

public interface ObjectStorage {

    void store(String key, InputStream in, long size, String contentType);

    void delete(String key);
}
