package document_processing.tobias_moreno.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MinioProperties.class, UploadProperties.class})
public class AppConfig {
}
