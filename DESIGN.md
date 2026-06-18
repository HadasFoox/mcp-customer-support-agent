# Design Document — CHEQ MCP Customer Support Agent

---

## What Was Built

A Model Context Protocol (MCP) server that exposes a single tool — `analyze_support_tickets` — to any MCP-compatible host (Claude Desktop, Claude Code). The tool answers natural-language business questions about customer support data and returns an executive report.

**Query flow (per call):**

```
Host (Claude Desktop / Claude Code)
  └─▶ analyze_support_tickets("business question")
        │
        ├─ 1. SQL generation loop (≤ 3 iterations)
        │       Inner LLM → SQL SELECT
        │       Java firewall validates → SQLite executes → returns ticket_ids
        │       Retries with error context if query is rejected or returns 0 rows
        │
        ├─ 2. Semantic search (bounded)
        │       SimpleVectorStore scans in-memory embeddings
        │       Filtered to ticket_ids from step 1 only
        │       Returns top-10 most relevant excerpts
        │
        └─ 3. Synthesis
                Inner LLM generates a 3–5 bullet executive report
                from the retrieved ticket excerpts
```

**Stack:** Java 21, Spring Boot 3.4, Spring AI 1.0, SQLite, Gemini API (chat + embeddings), STDIO transport.

---

## Why This Solution

**Two-phase retrieval (SQL → semantic)** was chosen deliberately over pure vector search alone. SQL handles structured filtering precisely — by queue, language, priority, app version — which semantic search cannot do reliably. Semantic search then handles the unstructured part — extracting meaning and themes from free-text ticket content. Each phase does what it is good at.

**MCP over STDIO** keeps the server zero-infrastructure: no HTTP port, no auth token rotation, no reverse proxy. The host manages the process lifecycle. This matches how support tooling is actually used internally — as a private assistant, not a public API.

**Gemini as the sole provider** — one API key, one endpoint, drives both chat and embeddings. Minimises credential surface and removes any dependency on a second vendor for this use case.

**SQLite + SimpleVectorStore** — no external database or vector service to provision. The full dataset is self-contained in two files that travel with the repo. Appropriate for the scale of this problem (750–10k tickets).

---

## What Was Deliberately Avoided

| Avoided | Reason |
|---|---|
| Prompt-only guardrails | Prompts can be overridden or jailbroken. All SQL safety is enforced in Java — the LLM never touches the connection directly. |
| Web server / REST API | Unnecessary for an MCP tool; STDIO transport is sufficient and eliminates an entire class of network exposure. |
| Streaming responses | Adds complexity for marginal UX gain at this scale; the synthesis step completes in 2–4 seconds. |
| Multiple AI providers | Two providers means two keys, two failure modes, two billing accounts. Single-provider keeps the footprint small. |
| Full dataset re-embed on startup | Warm-start detection (`isPopulated() && vectorFile.exists()`) skips all Gemini calls. Startup is instant, API costs are zero for repeat runs. |
| Console logging | `stdout` is the JSON-RPC transport wire. Any log line would corrupt the MCP frame stream and break the host connection. |

---

## What Would Change in Production

| Area | Change |
|---|---|
| **Data store** | Replace SQLite with PostgreSQL. Replace `SimpleVectorStore` (full in-memory scan) with `PgVectorStore` or `QdrantVectorStore` — the filter predicate becomes a true indexed pre-filter, no code changes to the tool required. |
| **Scale** | At 10k+ tickets, in-memory embeddings (~500 MB+) become a liability. The vector store swap above solves this. |
| **Data freshness** | The pre-seeded file is static. Production would ingest in near-real-time from the ticketing system (Zendesk/Salesforce webhook → ingest pipeline → live DB). |
| **Auth & multi-tenancy** | MCP tool calls are currently unauthenticated — fine for internal desktop use, not for a shared service. Production would add caller identity and scope the ticket query to the caller's team or permission set. |
| **Observability** | Add structured logging (JSON), trace IDs per tool call, and Gemini API latency/error metrics. Current file-only logging is sufficient for local development only. |
| **Cost controls** | Add per-query token counting and a hard cap. Currently the synthesis prompt can grow unbounded if many tickets match. |
| **LLM redundancy** | Single Gemini dependency is a single point of failure. Production would add a fallback provider or at minimum a circuit breaker. |

---

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| SQL injection via ticket content | Medium | High | Java `SqlQueryFirewall` rejects non-SELECT statements, comment patterns, and semicolon stacking. LLM never receives a raw DB connection. |
| LLM generates a runaway query (no LIMIT) | High | Medium | Firewall enforces `LIMIT 50` hard cap — it can only lower the LLM's limit, never raise it. |
| Prompt injection in ticket data | Medium | Medium | The synthesis step quotes ticket content as evidence, not instructions. Mitigation is partial — a determined attacker who controls ticket content could attempt to influence the report. |
| Gemini API outage | Low | High | No fallback currently. Production mitigation: circuit breaker, cached responses for repeated common questions. |
| 61 MB `vectorstore.json` bloats the repo over time | Low | Low | `processResources` already excludes it from the JAR. Long-term: move to Git LFS or object storage (S3) and load remotely on startup. |
| Stale embeddings after ticket schema change | Low | Medium | A schema change to the `tickets` table requires a cold-start re-ingest. This is a manual step today — production would automate it. |

---

## Business Impact at CHEQ

CHEQ's support tickets carry signal that is currently locked in a queue tool readable only by support agents. This agent unlocks that data for anyone with a business question.

**Immediate value:**
- **Product and R&D** can query: *"What SDK integration errors appear most in high-priority incidents this month?"* — surfacing bugs before they reach engineering via a ticket chain.
- **Customer Success** can query: *"Which enterprise customers are generating the most billing complaints?"* — enabling proactive outreach before churn.
- **Support leadership** can query: *"What languages are underserved in the Technical Support queue?"* — making a staffing case with evidence.

**Strategic value:**
- CHEQ's core product detects invalid traffic. Support tickets contain ground-truth signals about what customers believe is a false positive, what integrations break, and what detection edge cases cause friction. Mining this at scale — rather than reading tickets one by one — is a qualitatively different capability.
- The same architecture (MCP tool, two-phase retrieval, inner LLM synthesis) generalises to other internal datasets: sales call transcripts, NPS responses, app telemetry logs. The pattern is reusable.

**What this is not:** a replacement for BI or a reporting dashboard. It is a conversational layer over data that has no schema the business team would know how to query directly. The value is in removing the SQL barrier between a business question and an answer.
