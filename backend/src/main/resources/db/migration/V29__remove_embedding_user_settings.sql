DELETE FROM settings
WHERE key_name IN ('embedding_api_key', 'embedding_base_url', 'embedding_model');
