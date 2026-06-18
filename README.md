# CHEQ MCP Customer Support Agent

A production-grade MCP server that exposes a single tool to Claude Desktop and Claude Code:

```
analyze_support_tickets(business_question: String) → executive report
```

All internals — SQL generation, query firewall, SQLite store, vector search, inner LLM — are hidden from the host. The host only sees the one high-level tool.

---

## How it works

```
Claude Desktop / Claude Code  ──STDIO/JSON-RPC──▶  MCP Server
                                                        │
                                             1. Inner LLM → SQL SELECT
                                             2. Java firewall + read-only SQLite → ticket_ids
                                             3. Semantic search bounded to those ids (SimpleVectorStore)
                                             4. Inner LLM → executive report
```

**Guardrails (Java-enforced, not prompt-based)**
- SELECT-only; comment stripping; semicolon/stacking rejection
- Hard `LIMIT 50` cap (cap down only)
- 3-second JDBC query timeout; read-only SQLite connection
- Inner reasoning loop capped at 3 iterations
- Readiness gate — tool refuses until data is loaded

---

## Prerequisites

- **Java 21+** — [Download](https://adoptium.net/)
- **A Gemini API key** — [Get one free at Google AI Studio](https://aistudio.google.com/apikey)

No other dependencies need to be installed manually. Gradle downloads everything else.

---

## Environment variables

The only required variable is `GEMINI_API_KEY`. How you provide it depends on how you run the server:

| How you run | How to pass the key |
|---|---|
| Claude Desktop / Claude Code | `env` block in the MCP config (see below) — no `.env` file needed |
| Terminal (`./gradlew bootRun`) | `.env` file in the project root, or `export` in your shell |

**For terminal use only** — create a `.env` file in the project root (gitignored, loaded automatically):

```properties
GEMINI_API_KEY=your-key-here
```

Or export it in your shell:

```bash
export GEMINI_API_KEY=your-key-here
```

No other variables are needed. The pre-seeded data files are already included in the repo.

---

## Run locally (standalone)

No build step needed — Gradle handles compilation on first run:

```bash
./gradlew bootRun
```

The server starts and loads from the pre-seeded data files instantly. Check `logs/mcp.log` for:

```
Warm start: SQLite populated (750 tickets) and vectorstore.json loaded — no embedding calls.
```

The server is now an active STDIO process. It is meant to be managed by a Claude client (Desktop or Code), not invoked interactively.

---

## Connect to Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

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

Replace `/absolute/path/to/repo` with the actual path where you cloned this repo (e.g. `/Users/yourname/projects/mcp-customer-support-agent`).

Restart Claude Desktop — `analyze_support_tickets` will appear in the tool list.

---

## Connect to Claude Code (CLI)

Add the server to your Claude Code MCP configuration:

```bash
claude mcp add cheq-support -- /absolute/path/to/repo/gradlew bootRun
```

Or add it manually to `~/.claude/claude_code_config.json` (or `claude_desktop_config.json` — both formats are supported):

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

Then use the tool in any Claude Code session:

```
> analyze_support_tickets("What are the most common issues in the Technical Support queue?")
```

---

## Example prompts

- *"What are the most common issues in the Technical Support queue?"*
- *"Summarize billing complaints from German-speaking customers."*
- *"Which app versions generate the most high-priority incidents?"*
- *"How many open tickets are marked high priority, and what are the top themes?"*

---

## Pre-seeded data

The repo ships with `src/main/resources/support.sqlite` (750 tickets, ~924 KB) and `src/main/resources/vectorstore.json` (750 × 2 embedded documents, ~61 MB). The server detects both files on startup and skips ingestion entirely — no Gemini API calls, no CSV needed, ready in under a second.

To re-ingest from a different CSV, set these environment variables in `.env`:

```properties
DATASET_PATH=/path/to/your.csv
INGEST_MAX_ROWS=1000
```

Then delete `src/main/resources/support.sqlite` and `src/main/resources/vectorstore.json` and restart. A cold start will re-embed and overwrite both files.

---

## Run tests

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

All output goes to `logs/mcp.log` (rolling daily, 7-day retention). **stdout is reserved for JSON-RPC frames** — no console appender is configured, which is required for STDIO MCP transport.

To tail logs while running:

```bash
tail -f logs/mcp.log
```

---

## Scale-up path

`SimpleVectorStore` scans all in-memory vectors and applies the `ticket_id IN (...)` metadata filter — correctness guarantee, not a performance optimization. To scale beyond ~10k tickets, swap the `SimpleVectorStore` bean for `PgVectorStore` or `QdrantVectorStore`. The filter predicate pushes down as a true indexed pre-filter with no other code changes required.
