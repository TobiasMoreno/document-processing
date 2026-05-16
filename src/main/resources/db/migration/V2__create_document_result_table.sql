CREATE TABLE document_result (
    document_id     UUID PRIMARY KEY REFERENCES document(id) ON DELETE CASCADE,
    raw_text        TEXT        NOT NULL,
    document_type   VARCHAR(64),
    extracted_data  JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
