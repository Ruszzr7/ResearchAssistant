# ResearchAssistant

**English** | [简体中文](README.md)

**ResearchAssistant** is an AI assistant for research-paper reading. It reduces the need to switch repeatedly among papers, conversations, and notes, while improving the traceability and cross-session continuity of AI responses.

The system builds a persistent paper-understanding layer and versioned source index from parsed papers, and uses LangChain4j Tool Calling to let the Agent retrieve paper overviews, locate source evidence, and perform page actions when needed. With a two-column PDF reader, evidence deep links, layered context management, and multi-session memory, ResearchAssistant brings reading, questions, verification, and annotation into one workflow.

![Paper reading, evidence-grounded answers, and page interactions](docs/assets/readme/01-workbench-overview.png)

The image above shows the paper-reading workspace: the PDF reader is on the left, and a continuous conversation grounded in the current paper is on the right. Reading, selection, formulas, citations, and page actions can be handled without switching between multiple tools.

## Core Highlights

- **Versioned paper fact layer**: uses the PDF SHA-256 hash, PDFBox layout artifacts, and `SourceObject / SourceLocator` to build readable and citable sources for the current PDF version. When the PDF changes, old sources and page anchors are not reused for new answers.
- **Evidence-traceable research conversations**: the paper profile supports whole-paper understanding and retrieval planning, while source objects support fact verification. Citations in an answer can link to PDF pages, formulas, figures, tables, or page regions.
- **Constrained Agent page actions**: the Agent produces only page-action intent and trusted sources. The server resolves the target and issues an `ActionTicket` bound to the run and an expiration time; the frontend executes the action and submits the actual receipt.
- **One Agent with three on-demand Skills**: `paper-profile`, `paper-evidence`, and `paper-action` share the same paper context and handle whole-paper understanding, source retrieval, and page actions respectively.
- **Persistent research runtime**: stores research sessions, `Turn / Run / ToolCall`, context summaries, asynchronous tasks, and run events, supporting clarification, cancellation, timeouts, client-operation waits, and state recovery.

## Product Capabilities

- Manage paper libraries, folders, tags, reading status, and DOI / arXiv metadata;
- Search and zoom PDFs, select text, select formula regions, and create highlights, underlines, notes, and comments;
- Let users explicitly start paper understanding to generate paper structure, paper profiles, formula/figure sources, and deep-linkable evidence;
- Create multiple isolated research conversations for the same paper, preserving reading position, selections, attachments, messages, and citations;
- Ask the Agent about paper concepts, methods, formulas, and experimental findings, and hand explicit page actions to the frontend for execution;
- View the progress, result, failure, cancellation, and retry state of paper understanding, imports, and other asynchronous tasks in the task center.

## System Architecture

~~~mermaid
flowchart TD
    UI["Vue 3 reading and conversation workspace"] --> API["Spring Boot REST + SSE"]

    API --> PAPER["Paper and reading services"]
    API --> AGENT["Agent runtime"]
    API --> TASK["Persistent task manager"]

    PAPER --> PARSER["PDFBox layout and text parsing"]
    PARSER --> SOURCES["Versioned source catalog"]

    AGENT --> SKILLS["Three on-demand Agent Skills"]
    SKILLS --> PROFILE["paper-profile"]
    SKILLS --> EVIDENCE["paper-evidence"]
    SKILLS --> ACTION["paper-action"]
    EVIDENCE --> GROUND["Citation and version validation"]
    ACTION --> TICKET["ActionTicket and client receipt"]

    SOURCES --> DB["MySQL + Flyway"]
    AGENT --> DB
    TASK --> DB
    PAPER --> FILES["Local PDFs, images, and attachments"]
    GROUND --> UI
    TICKET --> UI
~~~

The frontend depends only on REST interfaces, SSE events, and page-operation results. Spring Boot orchestrates paper parsing, source indexing, Agent runtime state, and asynchronous tasks, while MySQL + Flyway persists structured data and the local file system stores PDFs, images, and conversation attachments.

## Core Execution Flow: Paper Facts and Page Actions Follow a Trusted Chain

~~~mermaid
flowchart TD
    A["User asks a question, attaches a selection, or requests a page action"] --> B["Create and persist Turn / Run"]
    B --> C["Assemble paper identity, history summary, recent conversation, and trusted selection"]
    C --> D["LangChain4j Tool Calling"]
    D --> E{"Activate the required Skill"}

    E -->|Whole-paper understanding| F["Read paper profile"]
    E -->|Paper fact| G["Retrieve and read SourceObject from the current version"]
    E -->|Page action| H["Resolve trusted source and action intent"]

    F --> I["Form the research direction"]
    G --> J["Validate version, location, and citation range on the server"]
    H --> K["Issue ActionTicket"]

    I --> L["Generate answer"]
    J --> L
    L --> M["Deep-link citation to the PDF page"]

    K --> N["Execute action in the browser with PDFium"]
    N --> O["Submit client receipt"]
    O --> P["Validate receipt and persist annotation"]
~~~

- A paper profile is a compressed understanding result and cannot replace source evidence;
- The model cannot invent page numbers, coordinates, or `sourceObjectId` values;
- The server must resolve page-action targets for the current version, and the frontend must execute the action and submit a receipt;
- `WAITING_USER` means that user clarification is required, while `WAITING_CLIENT` means that the browser is completing a page action.

## Agent Skill Design

The project uses one Agent with a shared paper context. A Skill is activated only when the current request needs it; the same paper is not split across three Agents that pass state between one another.

| Skill | Responsibility | Directly executes page actions? |
|---|---|---|
| `paper-profile` | Organizes the research question, overall method, contributions, experiments, and limitations to give the Agent a whole-paper view | No; read-only understanding |
| `paper-evidence` | Finds source text, formulas, figures, algorithms, experimental values, page numbers, and page locations | No; evidence retrieval only |
| `paper-action` | Converts explicit navigation, highlight, underline, note, or comment intent into trusted page actions | No; it generates a controlled action request, and actual execution requires an `ActionTicket` and client receipt |

The profile provides the “understanding direction,” evidence provides the “factual grounding,” and the action Skill provides “page behavior.” All three share the same session, paper version, and citation protocol.

## Key Engineering Design

### Separate paper profiles from source evidence

Paper-understanding tasks are started explicitly by the user. The backend uses PDFBox to generate structured paper artifacts with page and layout information, then creates a persistent paper profile and source catalog. The profile is suitable for answering “what is this paper about overall,” but formulas, experimental values, and final citations must be read again from `SourceObject` objects belonging to the current PDF version.

Current evidence retrieval performs deterministic lookup and read-back over local sources, structural metadata, and layout information bound to the PDF version. It does not use Embedding, a vector database, or a standalone RAG pipeline; the paper profile supports whole-paper understanding and retrieval navigation but cannot replace final source evidence.

### Isolate versions, sessions, and context

- All paper-derived artifacts are bound to the PDF `documentHash` and parser version. When a PDF is updated, the old structure, profile, sources, and anchors cannot serve the current answer;
- One paper can have multiple research sessions. Selections, attachments, formulas, and messages belong only to the current session or turn;
- `AgentContextAssembler` assembles system constraints, paper identity, history summary, recent conversation, trusted selections, and attachments;
- `AgentRunContextHarness` projects and folds tool results, controlling the budget of each model request;
- Raw messages remain in the database. Summaries provide long-term research state but do not claim lossless context preservation.

### ActionTicket and client receipts

Page actions are not direct model control of PDF coordinates. They pass through the following boundary:

1. The Agent confirms the requested action type and target;
2. The server resolves the page target from the current version's `sourceObjectId` and `SourceLocator`;
3. The server issues an `ActionTicket` bound to the Run, ToolCall, paper version, source, and expiration time;
4. PDFium executes the actual page action in the browser and returns the resulting coordinates and text anchor;
5. The server validates the ticket, version, location, and receipt before saving the annotation or completing the run.

### Persistent tasks and state recovery

A research request may involve multiple model calls, tool calls, user clarification, and browser operations, so runtime state cannot exist only inside one HTTP request. The project persists `Turn / Run / ToolCall` and task state, exposes control and query operations through REST, and pushes events through SSE.

Recoverable asynchronous tasks with a declared `task_type` persist their parameters, lease, attempt count, and result in MySQL and can be rescheduled after a service restart. In-process closure tasks are marked as failed after a restart instead of pretending that interrupted model generation was recovered.

## Evaluation Evidence

Evaluation data is located at `backend/src/test/resources/eval/agent-skill-300.jsonl`. Metric definitions are described in [`docs/agent-skill-metrics.md`](docs/agent-skill-metrics.md), and the evaluation-set design is documented in [`docs/skill-evaluation-plan.md`](docs/skill-evaluation-plan.md).

| Evaluation | Cases / data | Result |
|---|---:|---|
| `paper-profile` routing | 39 required positives | Precision 100.00% / Recall 94.87% / F1 97.37% |
| `paper-evidence` routing | 220 required positives | Precision 100.00% / Recall 98.18% / F1 99.08% |
| `paper-action` routing | 80 required positives | Precision 100.00% / Recall 100.00% / F1 100.00% |
| Result validity | 300 result records | 300 / 300; 0 failed records; 0 invalid requests |

## Feature Showcase

### Evidence deep links and paper citations

Citations in an answer can jump to the page and region of the current PDF while keeping the cited source and answer context visible on the right.

![Paper evidence linked back to the PDF page](docs/assets/readme/02-evidence-deep-link.png)

### Formula selection and LaTeX assistance

After selecting a formula region, the system provides formula-region recognition and editable LaTeX assistance. The final evidence still comes from the current PDF page.

![Formula selection and LaTeX recognition](docs/assets/readme/05-formula-selection.png)

### Agent page actions

When the user explicitly asks to “highlight the current selection in yellow,” the Agent interprets the intent, and the system performs the real page action and saves the result.

![Agent highlighting a selected paper passage](docs/assets/readme/04-agent-page-action.png)

### Research archives and multiple sessions

One paper can have multiple isolated research conversations. Message counts, run counts, and the most recently read page are stored in the research archive.

![Multiple papers and conversations in the research archive](docs/assets/readme/03-research-archive.png)

## Technology Stack

| Layer | Technology |
|---|---|
| Frontend | Vue 3, Vite, Element Plus, vxe-table, PDF.js, PDFium/WASM |
| Backend | Java 17, Spring Boot 3.2.6, Maven, MyBatis-Plus |
| Agent | LangChain4j 1.15.1, OpenAI-compatible API, Gemini Native, Agent Skills |
| Data | MySQL 8, Flyway; H2 for tests |
| PDF | Apache PDFBox, PDF.js, PDFium/WASM |
| Communication | HTTP REST, SSE |

## Data and Privacy

Paper, annotation, and research-session data are stored by default in the local MySQL instance and local file system. When `RA_MASTER_KEY` is configured, model API keys are stored using AES-GCM encryption.

When a third-party model provider is used, paper content, context, and questions required for the model interaction may be sent to that provider. Check the data-processing policy of the provider you actually use. Do not commit passwords, API keys, or the master key to Git.

The default local data directories are:

~~~text
data/papers/              # Imported paper PDFs
data/figures/             # Paper images or derived images
data/agent-attachments/   # Agent conversation attachments
runtime/                  # Local runtime logs and process state
~~~

## Running the Project

### Option A — Windows one-click startup

Requirements: Windows 10/11 x64, JDK 17+, Node.js 18+, npm, and MySQL 8.

Run the following from the repository root:

~~~bat
scripts\start-all.cmd
~~~

The script checks for and starts MySQL, creates the missing `research_assistant` database, initializes or upgrades the schema through Flyway, and then starts the Spring Boot backend and Vite frontend.

Default endpoints:

~~~text
Frontend: http://127.0.0.1:5173
Backend health check: http://127.0.0.1:8080/actuator/health
Database: 127.0.0.1:3306
~~~

Stop the services managed by the project scripts:

~~~bat
scripts\stop-all.cmd
~~~

### Option B — Windows source development

Make sure MySQL is running and configure the database account for the local environment:

~~~bat
set "SPRING_DATASOURCE_USERNAME=root"
set "SPRING_DATASOURCE_PASSWORD=your-local-password"
set "RA_MASTER_KEY=your-stable-local-key"
~~~

Start the backend:

~~~bat
cd backend
mvnw.cmd spring-boot:run
~~~

In another terminal, start the frontend:

~~~bat
cd frontend
npm.cmd ci
npm.cmd run dev
~~~

Configure the model provider, channel, Base URL, model name, and API key in the web settings page. When `RA_MASTER_KEY` is set, API keys are stored with AES-GCM encryption. Do not put passwords, API keys, or the master key in Git.

### Automated tests and build

Backend tests use an independent H2 database and do not read the local development database:

~~~bat
cd backend
mvnw.cmd clean test
~~~

Frontend unit tests and production build:

~~~bat
cd frontend
npm.cmd ci
npm.cmd run test:unit
npm.cmd run build
~~~

## Project Structure

~~~text
ResearchAssistant/
├── backend/
│   ├── src/main/java/com/research/assistant/
│   │   ├── controller/              REST, SSE, and page-operation endpoints
│   │   ├── service/agent/           Agent, Skills, evidence, and ActionTicket
│   │   ├── service/pdf/             PDFBox layout parsing and source indexing
│   │   ├── service/async/           Persistent async tasks, retry, and recovery
│   │   └── service/memory/          Paper understanding and paper profiles
│   ├── src/main/resources/db/       Flyway database migrations
│   └── src/test/                    Unit tests, integration tests, and evaluation data
├── frontend/                        Vue 3 reader, conversation, and task interfaces
├── skills/                          Three paper Agent Skill definitions
├── scripts/                         Windows startup, stop, and evaluation scripts
├── docs/                            Product specifications, evaluation plan, and metrics
├── data/                            Local papers, images, and attachments
└── README.md
~~~

## Documentation

- [`docs/Spec.md`](docs/Spec.md): product scope, architecture, and stable constraints;
- [`docs/agent-skill-metrics.md`](docs/agent-skill-metrics.md): evaluation results, metric definitions, and validity checks;
- [`docs/skill-evaluation-plan.md`](docs/skill-evaluation-plan.md): evaluation-set construction, gold-label rules, and execution process.
