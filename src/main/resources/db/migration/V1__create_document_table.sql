CREATE TABLE document (
    id                UUID         PRIMARY KEY,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(127) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    storage_path      VARCHAR(512) NOT NULL,
    status            VARCHAR(32)  NOT NULL,
    error_message     VARCHAR(1024),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ
);

CREATE INDEX idx_document_status ON document(status);
CREATE INDEX idx_document_created_at ON document(created_at DESC);
