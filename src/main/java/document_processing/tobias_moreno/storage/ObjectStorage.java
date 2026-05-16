package document_processing.tobias_moreno.storage;

import java.io.InputStream;

public interface ObjectStorage {

    void store(String key, InputStream in, long size, String contentType);

    InputStream read(String key);

    void delete(String key);
}
