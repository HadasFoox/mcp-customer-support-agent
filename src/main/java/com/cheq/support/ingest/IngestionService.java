package com.cheq.support.ingest;

import com.cheq.support.config.ReadinessGate;
import com.cheq.support.model.Ticket;
import com.cheq.support.repository.TicketReader;
import com.cheq.support.repository.TicketWriter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Offline ingestion phase. Runs once at startup ({@code @PostConstruct}) and is idempotent:
 *
 * <ul>
 *   <li><b>Warm start</b> — if SQLite is already populated AND {@code vectorstore.json} exists,
 *       just load the vectors (no embedding API calls) and open the readiness gate.</li>
 *   <li><b>Cold start</b> — parse the capped CSV, write tickets to SQLite, embed each ticket's
 *       {@code body} and {@code answer} into the vector store (metadata {@code {ticket_id, field}}),
 *       persist to {@code vectorstore.json}, then open the readiness gate.</li>
 * </ul>
 *
 * If the dataset is missing on a cold start, ingestion logs an error and leaves the gate closed,
 * so the tool reports "not ready" rather than serving empty results.
 */
@Component
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final CsvTicketParser parser;
    private final TicketReader reader;
    private final TicketWriter writer;
    private final SimpleVectorStore vectorStore;
    private final ReadinessGate readiness;
    private final String datasetPath;
    private final String vectorStorePath;
    private final int maxRows;

    public IngestionService(CsvTicketParser parser,
                            TicketReader reader,
                            TicketWriter writer,
                            SimpleVectorStore vectorStore,
                            ReadinessGate readiness,
                            @Value("${support.dataset.path}") String datasetPath,
                            @Value("${support.vectorstore.path}") String vectorStorePath,
                            @Value("${support.ingest.max-rows}") int maxRows) {
        this.parser = parser;
        this.reader = reader;
        this.writer = writer;
        this.vectorStore = vectorStore;
        this.readiness = readiness;
        this.datasetPath = datasetPath;
        this.vectorStorePath = vectorStorePath;
        this.maxRows = maxRows;
    }

    @PostConstruct
    public void ingest() {
        File vectorFile = new File(vectorStorePath);

        if (reader.isPopulated() && vectorFile.exists()) {
            vectorStore.load(vectorFile);
            readiness.markReady();
            log.info("Warm start: SQLite populated ({} tickets) and {} loaded — no embedding calls.",
                    reader.count(), vectorStorePath);
            return;
        }

        Path csv = Path.of(datasetPath);
        if (!Files.exists(csv)) {
            log.error("Dataset CSV not found at '{}'. Ingestion aborted; the tool will report "
                    + "not-ready until the dataset is present.", datasetPath);
            return; // gate stays closed
        }

        writer.initSchema();

        List<Ticket> tickets;
        try (Reader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            tickets = parser.parse(r, maxRows);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read dataset CSV at " + datasetPath, e);
        }
        writer.insertBatch(tickets);

        List<Document> documents = toDocuments(tickets);
        vectorStore.add(documents);
        vectorStore.save(vectorFile);

        readiness.markReady();
        log.info("Cold start complete: ingested {} tickets, embedded {} documents, saved to {}.",
                tickets.size(), documents.size(), vectorStorePath);
    }

    /** Two documents per ticket (body, answer); minimal metadata {ticket_id, field}. */
    private static List<Document> toDocuments(List<Ticket> tickets) {
        List<Document> documents = new ArrayList<>();
        for (Ticket t : tickets) {
            if (t.body() != null && !t.body().isBlank()) {
                documents.add(document(t.ticketId(), "body", t.body()));
            }
            if (t.answer() != null && !t.answer().isBlank()) {
                documents.add(document(t.ticketId(), "answer", t.answer()));
            }
        }
        return documents;
    }

    private static Document document(long ticketId, String field, String text) {
        return new Document(text, Map.of("ticket_id", ticketId, "field", field));
    }
}
