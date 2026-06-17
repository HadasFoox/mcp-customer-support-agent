# CHEQ MCP Customer Support Agent

A production-grade MCP server that exposes a single tool to Claude Desktop:

```
analyze_support_tickets(business_question: String) → executive report
```

All internals — SQL generation, query firewall, SQLite store, vector search, inner LLM — are private. Claude Desktop only sees the one high-level tool.

---

## How it works

```
Claude Desktop  ──STDIO/JSON-RPC──▶  MCP Server
                                         │
                              1. Inner LLM → SQL SELECT
                              2. Java firewall + read-only SQLite → ticket_ids
                              3. Semantic search bounded to those ids (SimpleVectorStore)
                              4. Inner LLM → executive report
```

**Guardrails (Java-enforced, never by prompt alone)**
- SELECT-only; comment stripping; semicolon/stacking rejection
- Hard `LIMIT 50` cap (cap down only)
- 3-second JDBC query timeout; read-only SQLite connection
- Inner reasoning loop capped at 3 iterations
- Readiness gate — tool refuses until ingestion is complete

---

## Prerequisites

- Java 21+
- A [Gemini API key](https://aistudio.google.com/apikey) (free tier works)

---

## 1 — Environment variables

Create a `.env` file in the project root (it is gitignored and loaded automatically):

```properties
GEMINI_API_KEY=your-key-here

# Optional overrides (defaults shown)
DATASET_PATH=data/customer-support-tickets/dataset-tickets-multi-lang-4-20k.csv
SQLITE_PATH=support.sqlite
VECTORSTORE_PATH=vectorstore.json
INGEST_MAX_ROWS=750
```

Or export them in your shell before running.

---

## 2 — Build

```bash
./gradlew bootJar
# produces build/libs/mcp-customer-support-agent-0.0.1-SNAPSHOT.jar
```

---

## 3 — Run standalone (smoke test)

The server speaks JSON-RPC over STDIO so it has no HTTP port to hit. You can verify startup by watching the log:

```bash
java -jar build/libs/mcp-customer-support-agent-0.0.1-SNAPSHOT.jar
tail -f logs/mcp.log
```

On first run (cold start):
```
Cold start complete: ingested 750 tickets, embedded 1500 documents, saved to vectorstore.json.
```

On subsequent runs (warm start — no embedding API calls):
```
Warm start: SQLite populated (750 tickets) and vectorstore.json loaded — no embedding calls.
```

---

## 4 — Connect to Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "cheq-support": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/mcp-customer-support-agent-0.0.1-SNAPSHOT.jar"
      ],
      "env": {
        "GEMINI_API_KEY": "your-key-here"
      }
    }
  }
}
```

Restart Claude Desktop. The tool `analyze_support_tickets` will appear in the tool list.

**Example prompts:**
- *"What are the most common issues in the Technical Support queue?"*
- *"Summarize billing complaints from German-speaking customers."*
- *"Which app versions generate the most high-priority incidents?"*

---

## 5 — Run tests

```bash
./gradlew test
```

Key test coverage:
- `SqlQueryFirewallTest` — semicolon stacking, non-SELECT rejection, LIMIT capping, comment injection
- `SafeSqlExecutorTest` — firewall integration, read-only connection
- `CsvTicketParserTest` — header-by-name mapping, multilingual, row cap, nullable version
- `IngestionServiceTest` — cold start (embeds), warm start (no re-embedding), missing CSV

---

## Logging

All output goes to `logs/mcp.log` (rolling). **stdout is reserved for JSON-RPC frames** — no console appender is configured, which is required for STDIO MCP transport.

---

## Scale-up path

`SimpleVectorStore` scans all in-memory vectors then applies the `ticket_id IN (...)` metadata filter — correctness guarantee, not a performance optimization. To scale beyond ~10 k tickets, swap the `SimpleVectorStore` bean for `PgVectorStore` or `QdrantVectorStore`; the filter predicate pushes down as a true indexed pre-filter with no other code changes.
