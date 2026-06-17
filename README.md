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

## 2 — Connect to Claude Desktop

No manual build step needed. Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "cheq-support": {
      "command": "/absolute/path/to/repo/gradlew",
      "args": ["bootRun"],
      "env": {
        "GEMINI_API_KEY": "your-key-here"
      }
    }
  }
}
```

Replace `/absolute/path/to/repo` with the actual path where you cloned this repo.  
Restart Claude Desktop — `analyze_support_tickets` will appear in the tool list.

**On first use (cold start)** the server embeds 750 tickets before accepting queries (~30–60 s depending on Gemini API latency). Watch progress in `logs/mcp.log`:
```
Cold start complete: ingested 750 tickets, embedded 1500 documents, saved to vectorstore.json.
```

Subsequent restarts load from disk instantly (warm start — zero embedding calls).

**Example prompts:**
- *"What are the most common issues in the Technical Support queue?"*
- *"Summarize billing complaints from German-speaking customers."*
- *"Which app versions generate the most high-priority incidents?"*

---

## 3 — Run tests

**Example prompts:**
- *"What are the most common issues in the Technical Support queue?"*
- *"Summarize billing complaints from German-speaking customers."*
- *"Which app versions generate the most high-priority incidents?"*

---

## 4 — Run tests

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
