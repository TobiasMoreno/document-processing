package document_processing.tobias_moreno.storage;

import document_processing.tobias_moreno.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class MinioObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorage.class);

    private final MinioClient client;
    private final String bucket;

    public MinioObjectStorage(MinioClient client, MinioProperties properties) {
        this.client = client;
        this.bucket = properties.getBucket();
    }

    @PostConstruct
    void ensureBucketExists() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                throw new IllegalStateException(
                        "Configured MinIO bucket '" + bucket + "' does not exist. "
                                + "Create it (e.g. via docker-compose minio-init) before starting the application.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to verify MinIO bucket '" + bucket + "'", e);
        }
    }

    @Override
    public void store(String key, InputStream in, long size, String contentType) {
        try {
            client.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .stream(in, size, -1)
                            .contentType(contentType)
                            .build());
        } catch (Exception e) {
            throw new StorageException("Failed to store object '" + key + "' in bucket '" + bucket + "'", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() != null ? e.errorResponse().code() : "";
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return;
            }
            throw new StorageException("Failed to delete object '" + key + "'", e);
        } catch (Exception e) {
            throw new StorageException("Failed to delete object '" + key + "'", e);
        }
    }
}
