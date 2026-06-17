# MCP Customer Support Agent — Implementation Plan

## Goal
A black-box MCP server exposing one tool to Claude Desktop:
`analyze_support_tickets(business_question: String)`
All internals (SQL, schema, vector store, inner LLM) stay private.

---

## Architecture Overview

```
Claude Desktop
     │  JSON-RPC / STDIO
     ▼
McpSupportServerApplication  (Spring Boot, WebApplicationType.NONE)
     │
     └── SupportAnalysisTool  (@Tool — the only public surface)
              │
              ├── ReadinessGate        guard: refuse until ingestion done
              │
              ├── ChatClient (Gemini)  inner LLM — SQL generation + synthesis
              │
              ├── SafeSqlExecutor      firewall → read-only SQLite
              │       └── SqlQueryFirewall  (SELECT-only, strip comments,
              │                             semicolon stacking, LIMIT 50 cap)
              │
              └── SimpleVectorStore    in-memory, persisted to vectorstore.json
                      └── Filter.Expression  ticket_id IN (sql-approved set)
```

---

## Provider Decision
Single Gemini provider via OpenAI-compatible endpoint (`GEMINI_API_KEY`).
- Chat model:      `gemini-2.5-flash`
- Embedding model: `gemini-embedding-001`
- Spring AI starter: `spring-ai-starter-model-openai` pointed at Gemini base URL

*Original plan called for Anthropic (chat) + OpenAI text-embedding-3-large, but
single-provider Gemini was chosen in Step 1 and the whole codebase follows that.*

---

## Package Layout

```
com.cheq.support
├── McpSupportServerApplication.java   entry point + MethodToolCallbackProvider bean
├── model/
│   └── Ticket.java                    record — CSV row
├── config/
│   ├── ReadinessGate.java             AtomicBoolean flag set at end of ingestion
│   └── VectorStoreConfig.java         SimpleVectorStore bean
├── repository/
│   ├── SqliteConnectionFactory.java   opens writable / read-only connections
│   ├── TicketReader.java              isPopulated(), count()
│   ├── TicketWriter.java              initSchema(), insertBatch()
│   └── safesql/
│       ├── SqlQueryFirewall.java      sanitize() — comment strip, semicolons, SELECT-only, LIMIT cap
│       ├── SafeSqlExecutor.java       execute() — firewall + read-only + 3s timeout
│       └── UnsafeSqlException.java
├── ingest/
│   ├── CsvTicketParser.java           header-by-name CSV parser, synthesizes ticket_id
│   └── IngestionService.java          @PostConstruct warm/cold start
└── agent/
    └── SupportAnalysisTool.java       @Tool — the public MCP surface  ← STEP 5
```

---

## Steps

### Step 1 — MCP server skeleton ✅ (committed)
- `McpSupportServerApplication` — `WebApplicationType.NONE`, banner off
- `application.yml` — Gemini via OpenAI compat, STDIO MCP, file-only logging config
- `logback-spring.xml` — file-only (`logs/mcp.log`), NO console appender

### Step 2 — SQLite ticket store ✅ (committed)
- `Ticket` record — 14 fields; `ticket_id` synthesized as 0-based row index
- `SqliteConnectionFactory` — single place for writable / read-only connections
- `TicketWriter` — `initSchema()` (CREATE TABLE + 9 indexes), `insertBatch()` (single transaction)
- `TicketReader` — `isPopulated()`, `count()`

### Step 3 — SQL query firewall + executor ✅ (committed)
- `SqlQueryFirewall.sanitize()`:
  1. Strip `--` and `/* */` comments (string-literal aware)
  2. Strip single trailing `;`; reject any remaining `;` (no stacking)
  3. Must start with `SELECT`
  4. Wrap as `SELECT * FROM (<query>) LIMIT 50` (cap down only)
- `SafeSqlExecutor.execute()` — runs on read-only connection with 3s timeout
- `UnsafeSqlException` — signals firewall rejection

### Step 4 — Ingest pipeline ⏳ (written, not yet committed)
- `ReadinessGate` — `AtomicBoolean`; `markReady()` called only at end of ingestion
- `VectorStoreConfig` — `SimpleVectorStore` bean
- `CsvTicketParser` — reads by header name; blank/missing → null; non-numeric version → null
- `IngestionService` (`@PostConstruct`):
  - **Warm start**: SQLite populated AND `vectorstore.json` exists → load vectors, open gate
  - **Cold start**: parse CSV (capped), write to SQLite, embed body+answer, save, open gate
  - **Missing dataset**: log error, gate stays closed; tool reports "not ready"
  - Metadata: `ticket_id` stored as `String` (safe filter comparison after JSON round-trip)

### Step 5 — Agent tool 🔲 (to implement)
**File**: `agent/SupportAnalysisTool.java`

**Flow**:
```
analyzeSupportTickets(businessQuestion)
│
├── 1. Guard check  — readinessGate.isReady() → return "not ready" message if false
│
├── 2. SQL loop (max 3 iterations)
│   ├── ChatClient prompt → LLM generates SQL SELECT (schema + question + prior error)
│   ├── SafeSqlExecutor.execute(sql) → rows  (or catch UnsafeSqlException / RuntimeException)
│   ├── Extract ticket_ids from "ticket_id" column of rows
│   └── Break if non-empty; else set feedback error and retry
│
├── 3. If still empty after 3 tries → return "no tickets matched" message
│
├── 4. Semantic search
│   └── SimpleVectorStore.similaritySearch(
│           SearchRequest(query=businessQuestion, topK=10,
│                         filterExpression="ticket_id in ['id1','id2',...]"))
│       Note: SimpleVectorStore scans ALL vectors then filters — correctness gate,
│             not a performance optimization. pgvector/Qdrant = scale-up path.
│
└── 5. Synthesis
    └── ChatClient prompt → LLM writes executive report from retrieved doc excerpts
```

**Wiring** — add to `McpSupportServerApplication`:
```java
@Bean
public MethodToolCallbackProvider toolCallbackProvider(SupportAnalysisTool tool) {
    return MethodToolCallbackProvider.builder().toolObjects(tool).build();
}
```

---

## Dependency Coordinates

| Artifact | Version | Notes |
|---|---|---|
| `org.springframework.boot:spring-boot-starter` | 3.4.2 (plugin) | via BOM |
| `org.springframework.ai:spring-ai-bom` | 1.0.1 | imports all Spring AI versions |
| `org.springframework.ai:spring-ai-starter-mcp-server` | 1.0.1 | STDIO MCP transport |
| `org.springframework.ai:spring-ai-starter-model-openai` | 1.0.1 | chat + embeddings via Gemini compat |
| `org.xerial:sqlite-jdbc` | 3.53.2.0 | pure-Java SQLite |
| `org.apache.commons:commons-csv` | 1.14.1 | CSV parsing |

No new dependencies needed for Step 5.

---

## Guardrails Summary (Java-enforced, never by prompt)

| Guardrail | Where |
|---|---|
| SELECT-only SQL | `SqlQueryFirewall` |
| Comment stripping | `SqlQueryFirewall` |
| Semicolon / stacking rejection | `SqlQueryFirewall` |
| Hard LIMIT 50 cap (cap down only) | `SqlQueryFirewall` |
| 3-second query timeout | `SafeSqlExecutor` |
| Read-only JDBC connection | `SqliteConnectionFactory` + `SafeSqlExecutor` |
| Reasoning loop capped at 3 | `SupportAnalysisTool` |
| Readiness gate | `ReadinessGate` + `SupportAnalysisTool` |

---

## Acceptance Criteria

- [ ] `./gradlew test` passes (all existing + new tests)
- [ ] Firewall tests: semicolon stacking, non-SELECT, LIMIT capping, comment injection
- [ ] Ingestion tests: cold start (embeds), warm start (no re-embedding), missing CSV
- [ ] No console output — all logging goes to `logs/mcp.log`
- [ ] Fat jar (`./gradlew bootJar`) builds without errors
- [ ] Claude Desktop can call `analyze_support_tickets` and get a report
- [ ] Server refuses calls until ingestion completes

---

## Environment Variables

| Var | Purpose |
|---|---|
| `GEMINI_API_KEY` | Gemini chat + embeddings |
| `DATASET_PATH` | Path to the HF CSV (default: `data/.../dataset-tickets-multi-lang-4-20k.csv`) |
| `SQLITE_PATH` | SQLite file path (default: `support.sqlite`) |
| `VECTORSTORE_PATH` | Vector store JSON (default: `vectorstore.json`) |
| `INGEST_MAX_ROWS` | Row cap for first run (default: 750) |
