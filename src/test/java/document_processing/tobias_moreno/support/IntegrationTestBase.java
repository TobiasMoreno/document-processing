package document_processing.tobias_moreno.support;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    static final String BUCKET = "documents";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("documents")
                    .withUsername("documents")
                    .withPassword("documents");

    @Container
    static final MinIOContainer MINIO =
            new MinIOContainer(DockerImageName.parse("minio/minio:latest"))
                    .withUserName("minioadmin")
                    .withPassword("minioadmin");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.storage.minio.endpoint", MINIO::getS3URL);
        registry.add("app.storage.minio.access-key", MINIO::getUserName);
        registry.add("app.storage.minio.secret-key", MINIO::getPassword);
        registry.add("app.storage.minio.bucket", () -> BUCKET);

        try {
            MinioClient bootstrap = MinioClient.builder()
                    .endpoint(MINIO.getS3URL())
                    .credentials(MINIO.getUserName(), MINIO.getPassword())
                    .build();
            boolean exists = bootstrap.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
            if (!exists) {
                bootstrap.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to bootstrap MinIO bucket", e);
        }
    }
}
