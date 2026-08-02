DELETE FROM settings
WHERE key_name IN (
    'vector_store_provider', 'qdrant_host', 'qdrant_port', 'qdrant_use_tls',
    'qdrant_api_key', 'qdrant_collection', 'rag_rerank_enabled',
    'rag_rerank_top_k', 'rag_answer_top_k', 'rag_rerank_min_chunks'
);
