DELETE FROM settings WHERE key_name = 'rag_enabled';

DROP TABLE IF EXISTS paper_chunk;
DROP TABLE IF EXISTS rag_index_version;
DROP TABLE IF EXISTS rag_index_state;
