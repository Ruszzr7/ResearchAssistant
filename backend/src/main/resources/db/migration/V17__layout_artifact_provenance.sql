-- P3-A: persist parser fallback decisions and quality gain with each artifact.
ALTER TABLE paper_layout_artifact
    ADD COLUMN provenance_json TEXT NULL AFTER blocks_json;
