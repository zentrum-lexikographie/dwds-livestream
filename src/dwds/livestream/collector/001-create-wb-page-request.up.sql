CREATE TABLE IF NOT EXISTS wb_page_request (
    id SERIAL PRIMARY KEY,
    ts TIMESTAMP NOT NULL,
    lemma VARCHAR(128) NOT NULL,
    article_type VARCHAR(64),
    article_source VARCHAR(64),
    article_date date
);

CREATE INDEX IF NOT EXISTS wb_page_request_ts ON wb_page_request (ts);
CREATE INDEX IF NOT EXISTS wb_page_request_lemma ON wb_page_request (lemma);
CREATE INDEX IF NOT EXISTS wb_page_request_source ON wb_page_request (article_source);
