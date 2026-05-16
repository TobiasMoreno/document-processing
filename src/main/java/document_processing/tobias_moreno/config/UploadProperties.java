package document_processing.tobias_moreno.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "app.upload")
public class UploadProperties {

    private long maxFileSize;
    private List<String> allowedContentTypes = List.of();

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public List<String> getAllowedContentTypes() {
        return allowedContentTypes;
    }

    public void setAllowedContentTypes(List<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }

    public Set<String> allowedContentTypesAsSet() {
        return Set.copyOf(allowedContentTypes);
    }
}
