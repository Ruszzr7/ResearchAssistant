-- Keep persisted paper metadata capacity aligned with the API contract.
ALTER TABLE paper
    MODIFY COLUMN authors MEDIUMTEXT NULL,
    MODIFY COLUMN source VARCHAR(500) NULL,
    MODIFY COLUMN doi VARCHAR(200) NULL,
    MODIFY COLUMN semantic_scholar_id VARCHAR(200) NULL,
    MODIFY COLUMN source_url VARCHAR(2000) NULL,
    MODIFY COLUMN `abstract` MEDIUMTEXT NULL,
    MODIFY COLUMN keywords TEXT NULL;
