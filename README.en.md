# Research Assistant

English version; 中文说明: [README.md](README.md)

Research Assistant is a local research companion for CS, AI, and EE researchers. It connects paper management, PDF reading, paper understanding, continuous research conversations, and research archives. Persisted Agent runs and paper-source links make answers traceable to the current PDF version.

## Main capabilities

- **Paper library**: folders, tags, filters, batch actions, PDF import, and DOI/arXiv metadata completion.
- **PDF reading**: search, zoom, highlights, underlines, text selection, formula selection, notes, and annotation navigation.
- **Paper understanding**: layout-aware PDF processing, versioned sources, paper profiles, evidence retrieval, and continuous research conversations.
- **Agent workbench**: native LangChain4j tool-calling loops with persistent Turns, Runs, ToolCalls, clarification, and client-operation waits.
- **Page operations**: explicit requests can create navigation, highlight, underline, note, or comment actions through server-issued ActionTickets and client receipts.
- **Research archives and tasks**: persisted research messages, reading progress, annotations, and asynchronous task state with cancellation, retry, timeout, and restart recovery.

The project includes three paper Agent Skills:

| Skill | Responsibility |
| --- | --- |
| paper-profile | Organizes whole-paper information such as the research problem, overall method, contributions, experiments, and limitations |
| paper-evidence | Finds formulas, figures, tables, algorithms, experiment values, page locations, and citable source text |
| paper-action | Converts explicit page-operation requests into trusted navigation, highlight, underline, note, or comment actions |

## Technology and architecture

| Layer | Technology |
| --- | --- |
| Frontend | Vue 3, Vite, Element Plus, vxe-table, PDF.js, and PDFium/WASM interaction |
| Backend | Java 17, Spring Boot 3.2.6, Maven, and MyBatis-Plus |
| AI | LangChain4j 1.15.1, OpenAI-compatible APIs, and Gemini Native |
| Database | MySQL 8 and Flyway |
| PDF | Apache PDFBox for layout/text facts and PDFium for browser selection, search, and precise linking |
| Local storage | PDFs, images, Agent attachments, and runtime logs |

~~~text
Vue 3 / Vite
      │ REST / SSE
      ▼
Spring Boot / LangChain4j
      ├─ paper library, reading progress, annotations, and research archives
      ├─ Agent Turn / Run / ToolCall and asynchronous tasks
      ├─ paper structure, profiles, sources, citations, and page targets
      └─ three on-demand paper Skills
      │
      ├─ MySQL + Flyway for structured data and task state
      └─ local files for PDFs, images, and attachments
~~~

## Windows source startup

### Requirements

- Windows 10/11 x64;
- JDK 17 or newer, including javac;
- Node.js 18 or newer with npm;
- MySQL 8 with a reachable local instance;
- Git, only needed to clone the repository.

### Start all services

From the repository root, run:

~~~bat
scripts\start-all.cmd
~~~

The scripts will:

1. Find JDK 17;
2. Check and start the configured MySQL instance;
3. Create the research_assistant database when the configured account has permission;
4. Let Flyway initialize or upgrade the schema;
5. Start the Spring Boot backend and Vite frontend;
6. Wait until the database, backend health check, and frontend are ready.

Default endpoints:

~~~text
Frontend: http://127.0.0.1:5173
Backend health: http://127.0.0.1:8080/actuator/health
Database: 127.0.0.1:3306
~~~

### Database and key configuration

Database settings can be overridden through environment variables:

~~~bat
set "SPRING_DATASOURCE_USERNAME=root"
set "SPRING_DATASOURCE_PASSWORD=your-local-password"
set "RA_MASTER_KEY=your-stable-local-key"
~~~

Keep RA_MASTER_KEY stable. Once configured, API keys saved in the settings page are encrypted with AES-GCM; changing the key makes previously saved API keys unreadable. Never commit passwords, API keys, or the master key.

Configure the model provider, channel, base URL, model name, and API key in the web settings page. Agent calls require a working OpenAI-compatible or Gemini API and sufficient provider quota.

## Tests and build verification

Backend tests use an independent H2 database and do not read the local development database:

~~~bat
cd backend
mvnw.cmd clean test
~~~

Run frontend unit tests and a production build with:

~~~bat
cd frontend
npm.cmd ci
npm.cmd run test:unit
npm.cmd run build
~~~

Source tests, Skill routing tests, and the evaluation dataset are under backend/src/test.

## Skill evaluation

The frozen evaluation set contains 30 papers and 10 cases per paper, for 300 multi-label cases. Each case runs once; Precision, Recall, and F1 are calculated independently for each Skill:

| Skill | Precision | Recall | F1 |
| --- | ---: | ---: | ---: |
| paper-profile | 100.00% | 94.87% | 97.37% |
| paper-evidence | 100.00% | 98.18% | 99.08% |
| paper-action | 100.00% | 100.00% | 100.00% |

See [docs/agent-skill-metrics.md](docs/agent-skill-metrics.md) for metric definitions and validity checks, and [docs/skill-evaluation-plan.md](docs/skill-evaluation-plan.md) for dataset and gold-label rules.

## Data directories

Default local data directories are:

~~~text
data/papers/              # imported paper PDFs
data/figures/             # paper or derived images
data/agent-attachments/   # Agent conversation attachments
~~~

## Repository layout

~~~text
backend/                  # Spring Boot, controllers, services, mappers, and Agent code
frontend/                 # Vue 3, PDF reader, and workbench
skills/                   # paper-profile, paper-evidence, paper-action
scripts/                  # Windows startup, stop, and evaluation scripts
docs/                     # product specification, evaluation plan, and metrics
data/                     # local papers and attachments
~~~
