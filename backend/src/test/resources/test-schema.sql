CREATE TABLE settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_name VARCHAR(100) NOT NULL UNIQUE,
    value VARCHAR(2000) NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE folder (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    parent_id BIGINT,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tag (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE paper (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    authors TEXT,
    year INT,
    source VARCHAR(300),
    doi VARCHAR(200) UNIQUE,
    arxiv_id VARCHAR(100),
    semantic_scholar_id VARCHAR(100),
    source_url VARCHAR(1000),
    citation_count INT DEFAULT 0,
    `abstract` TEXT,
    keywords VARCHAR(500),
    pdf_path VARCHAR(500),
    acquisition_method VARCHAR(30),
    folder_id BIGINT,
    reading_status VARCHAR(20) DEFAULT 'UNREAD',
    pinned BOOLEAN DEFAULT FALSE,
    page_count INT,
    current_page INT DEFAULT 0,
    read_seconds INT DEFAULT 0,
    last_read_at TIMESTAMP,
    ai_summary TEXT,
    processing_status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE paper_tag (
    paper_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (paper_id, tag_id)
);

CREATE TABLE paper_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    chunk_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    embedding_json TEXT NOT NULL,
    source VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE note (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255),
    content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE paper_note_link (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    note_id BIGINT NOT NULL,
    page INT,
    coordinates_json TEXT,
    anchor_text TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE reading_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    start_date DATE,
    end_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE reading_plan_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    paper_id BIGINT NOT NULL,
    deadline DATE,
    priority INT DEFAULT 0,
    status VARCHAR(32) DEFAULT 'TODO',
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (plan_id) REFERENCES reading_plan(id) ON DELETE CASCADE
);

CREATE TABLE writing_project (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    topic VARCHAR(500),
    outline_json TEXT,
    related_work TEXT,
    draft_content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE writing_project_paper (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    paper_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
