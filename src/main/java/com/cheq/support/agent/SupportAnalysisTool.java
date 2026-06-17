package com.cheq.support.agent;

import com.cheq.support.config.ReadinessGate;
import com.cheq.support.repository.safesql.SafeSqlExecutor;
import com.cheq.support.repository.safesql.UnsafeSqlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The single public MCP surface: {@code analyze_support_tickets}.
 *
 * <p>Online flow per call:
 * <ol>
 *   <li>Guard — refuse until ingestion is done.</li>
 *   <li>SQL loop (≤3 iterations) — inner LLM generates a SELECT; the Java firewall
 *       sanitizes and executes it; ticket_ids are extracted from the result set.</li>
 *   <li>Semantic search — SimpleVectorStore scans all in-memory vectors then applies
 *       a {@code ticket_id IN (...)} metadata filter, bounding results to the
 *       SQL-approved set. This guarantees correctness, not algorithmic speedup;
 *       pgvector/Qdrant would push the same predicate down as a true indexed
 *       pre-filter — a drop-in swap of the VectorStore bean.</li>
 *   <li>Synthesis — inner LLM writes an executive report from the retrieved excerpts.</li>
 * </ol>
 */
@Component
public class SupportAnalysisTool {

    private static final Logger log = LoggerFactory.getLogger(SupportAnalysisTool.class);

    private static final int MAX_ITERATIONS = 3;
    private static final int SEMANTIC_TOP_K = 10;

    private static final String SQL_SYSTEM_PROMPT =
            "You are a SQLite expert. Return raw SQL only — no markdown, no explanation. "
            + "Always include ticket_id in the SELECT list. "
            + "Use only SELECT statements; never INSERT, UPDATE, DELETE, or DROP.";

    private static final String SCHEMA_CONTEXT = """
            Table: tickets
              ticket_id INTEGER, subject TEXT,
              type TEXT (e.g. "Incident","Request"),
              queue TEXT (e.g. "Technical Support","Billing and Payments","Sales"),
              priority TEXT ("high"|"medium"|"low"),
              language TEXT (ISO 639-1, e.g. "en","de","ja","he"),
              version INTEGER nullable (e.g. 120),
              tag_1 TEXT nullable, tag_2 TEXT nullable, tag_3 TEXT nullable,
              tag_4 TEXT nullable, tag_5 TEXT nullable
            Always SELECT ticket_id.""";

    private final ChatClient chatClient;
    private final SafeSqlExecutor safeSqlExecutor;
    private final SimpleVectorStore vectorStore;
    private final ReadinessGate readinessGate;

    public SupportAnalysisTool(ChatClient.Builder chatClientBuilder,
                               SafeSqlExecutor safeSqlExecutor,
                               SimpleVectorStore vectorStore,
                               ReadinessGate readinessGate) {
        this.chatClient = chatClientBuilder.build();
        this.safeSqlExecutor = safeSqlExecutor;
        this.vectorStore = vectorStore;
        this.readinessGate = readinessGate;
    }

    @Tool(description = "Analyses customer-support tickets and returns an executive report answering the given business question. "
            + "Internally performs SQL-filtered semantic retrieval over the full multilingual ticket dataset.")
    public String analyzeSupportTickets(
            @ToolParam(description = "The business question to answer, e.g. 'What are the most common issues in the Technical Support queue?'")
            String businessQuestion) {

        if (!readinessGate.isReady()) {
            return "The server is still ingesting the dataset. Please try again in a moment.";
        }

        List<Long> ticketIds = new ArrayList<>();
        String lastError = null;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            String sql = generateSql(businessQuestion, lastError);
            log.info("Iteration {}: generated SQL: {}", i + 1, sql);

            try {
                List<Map<String, Object>> rows = safeSqlExecutor.execute(sql);
                ticketIds = extractTicketIds(rows);
                if (!ticketIds.isEmpty()) {
                    log.info("Iteration {}: {} ticket(s) matched.", i + 1, ticketIds.size());
                    break;
                }
                lastError = "The query returned 0 rows. Please broaden the filter criteria or try different column values.";
                log.info("Iteration {}: 0 rows — retrying.", i + 1);
            } catch (UnsafeSqlException e) {
                lastError = "The query was rejected by the security firewall: " + e.getMessage()
                        + ". Please write a valid SELECT against the tickets table with no semicolons or non-SELECT statements.";
                log.warn("Iteration {}: firewall rejected query — {}", i + 1, e.getMessage());
            } catch (Exception e) {
                lastError = "The query failed to execute: " + e.getMessage() + ". Please simplify the query.";
                log.warn("Iteration {}: query execution error — {}", i + 1, e.getMessage());
            }
        }

        if (ticketIds.isEmpty()) {
            return "No tickets matched the criteria after " + MAX_ITERATIONS + " attempts. "
                    + "Try a broader business question or different filter values.";
        }

        List<Document> docs = semanticSearch(businessQuestion, ticketIds);
        if (docs.isEmpty()) {
            return "Tickets were found in the database but no semantic matches were retrieved for the question: "
                    + businessQuestion;
        }

        return synthesizeReport(businessQuestion, docs);
    }

    // -------------------------------------------------------------------------
    // Inner-LLM calls
    // -------------------------------------------------------------------------

    private String generateSql(String businessQuestion, String priorError) {
        String errorContext = priorError != null
                ? "\nPrior error: " + priorError
                : "";

        String prompt = """
                Generate a SQLite SELECT for this business question. Return raw SQL only.

                Schema: %s
                Question: %s%s""".formatted(SCHEMA_CONTEXT, businessQuestion, errorContext);

        String raw = chatClient.prompt().system(SQL_SYSTEM_PROMPT).user(prompt).call().content();
        return extractSql(raw);
    }

    private String synthesizeReport(String businessQuestion, List<Document> docs) {
        String excerpts = docs.stream()
                .map(d -> {
                    String content = d.getText();
                    String preview = content.length() > 400
                            ? content.substring(0, 400) + "…"
                            : content;
                    Object id = d.getMetadata().get("ticket_id");
                    Object field = d.getMetadata().get("field");
                    return "  [ticket_id=%s field=%s] %s".formatted(id, field, preview);
                })
                .collect(Collectors.joining("\n"));

        String prompt = """
                You are a business analyst. Answer the following business question about customer-support tickets \
                using only the evidence provided below. Write a concise executive report: \
                3-5 bullet points covering key patterns, frequencies, and actionable insights. \
                Do not invent data beyond what is shown.

                Business question: %s

                Relevant ticket excerpts:
                %s
                """.formatted(businessQuestion, excerpts);

        return chatClient.prompt().user(prompt).call().content();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Strip markdown code fences (```sql … ```) that the LLM may wrap the query in. */
    private static String extractSql(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    private static List<Long> extractTicketIds(List<Map<String, Object>> rows) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object val = row.get("ticket_id");
            if (val instanceof Number n) {
                ids.add(n.longValue());
            }
        }
        return ids;
    }

    private List<Document> semanticSearch(String question, List<Long> ticketIds) {
        // Build: ticket_id in [0, 1, 2, ...]
        // SimpleVectorStore scans ALL vectors then applies this filter in-memory —
        // correctness bound, not a performance pre-filter.
        String inList = ticketIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        String filterExpr = "ticket_id in [" + inList + "]";

        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(SEMANTIC_TOP_K)
                        .filterExpression(filterExpr)
                        .build());
    }
}
