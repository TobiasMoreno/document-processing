package document_processing.tobias_moreno.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.kafka")
public class KafkaProperties {

    private String bootstrapServers = "localhost:9092";
    private Topics topics = new Topics();
    private Consumer consumer = new Consumer();
    private long publishTimeoutMs = 5_000L;

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public Topics getTopics() {
        return topics;
    }

    public void setTopics(Topics topics) {
        this.topics = topics;
    }

    public long getPublishTimeoutMs() {
        return publishTimeoutMs;
    }

    public void setPublishTimeoutMs(long publishTimeoutMs) {
        this.publishTimeoutMs = publishTimeoutMs;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    public void setConsumer(Consumer consumer) {
        this.consumer = consumer;
    }

    public static class Consumer {
        private String groupId = "document-processor";
        private int concurrency = 1;

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }
    }

    public static class Topics {
        private String documentUploaded = "document.uploaded";

        public String getDocumentUploaded() {
            return documentUploaded;
        }

        public void setDocumentUploaded(String documentUploaded) {
            this.documentUploaded = documentUploaded;
        }
    }
}
