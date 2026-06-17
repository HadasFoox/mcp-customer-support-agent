package com.cheq.support.ingest;

import com.cheq.support.config.ReadinessGate;
import com.cheq.support.repository.SqliteConnectionFactory;
import com.cheq.support.repository.TicketReader;
import com.cheq.support.repository.TicketWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionServiceTest {

    private static final String CSV = """
            subject,body,answer,type,queue,priority,language,version,tag_1,tag_2,tag_3,tag_4,tag_5
            Login fails,Cannot log in,Reset password,Incident,Technical Support,high,en,120,auth,,,,
            Rechnung falsch,Betrag stimmt nicht,Korrigiert,Request,Billing and Payments,low,de,,billing,,,,
            ログイン,できない,対応しました,Incident,Technical Support,medium,ja,200,auth,,,,
            """;

    /** Fake embedding model that returns a fixed vector and counts how often it is invoked. */
    static final class CountingEmbeddingModel implements EmbeddingModel {
        int embedCalls = 0;

        private float[] vector() {
            return new float[] {0.1f, 0.2f, 0.3f};
        }

        @Override
        public float[] embed(Document document) {
            embedCalls++;
            return vector();
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            List<String> instructions = request.getInstructions();
            for (int i = 0; i < instructions.size(); i++) {
                embedCalls++;
                embeddings.add(new Embedding(vector(), i));
            }
            return new EmbeddingResponse(embeddings);
        }
    }

    private Path writeCsv(Path dir) throws IOException {
        Path csv = dir.resolve("tickets.csv");
        Files.writeString(csv, CSV);
        return csv;
    }

    @Test
    void coldStartIngestsAndEmbeds_warmStartLoadsWithoutEmbedding(@TempDir Path dir) throws IOException {
        Path csv = writeCsv(dir);
        String sqlitePath = dir.resolve("support.sqlite").toString();
        String vectorPath = dir.resolve("vectorstore.json").toString();

        SqliteConnectionFactory connections = new SqliteConnectionFactory(sqlitePath);
        TicketReader reader = new TicketReader(connections);
        TicketWriter writer = new TicketWriter(connections);
        CsvTicketParser parser = new CsvTicketParser();

        // --- Cold start ---
        CountingEmbeddingModel coldModel = new CountingEmbeddingModel();
        SimpleVectorStore coldStore = SimpleVectorStore.builder(coldModel).build();
        ReadinessGate coldGate = new ReadinessGate();

        IngestionService cold = new IngestionService(
                parser, reader, writer, coldStore, coldGate, csv.toString(), vectorPath, 100);
        cold.ingest();

        assertThat(coldGate.isReady()).isTrue();
        assertThat(reader.count()).isEqualTo(3);                 // 3 tickets inserted
        assertThat(coldModel.embedCalls).isGreaterThan(0);       // embeddings computed
        assertThat(Files.exists(Path.of(vectorPath))).isTrue();  // vector store persisted

        // --- Warm start (simulated restart: fresh store + gate, same paths) ---
        CountingEmbeddingModel warmModel = new CountingEmbeddingModel();
        SimpleVectorStore warmStore = SimpleVectorStore.builder(warmModel).build();
        ReadinessGate warmGate = new ReadinessGate();

        IngestionService warm = new IngestionService(
                parser, reader, writer, warmStore, warmGate, csv.toString(), vectorPath, 100);
        warm.ingest();

        assertThat(warmGate.isReady()).isTrue();
        assertThat(warmModel.embedCalls).isZero();               // idempotent: no re-embedding
    }

    @Test
    void missingDatasetLeavesGateClosed(@TempDir Path dir) {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(dir.resolve("s.sqlite").toString());
        ReadinessGate gate = new ReadinessGate();

        IngestionService svc = new IngestionService(
                new CsvTicketParser(), new TicketReader(connections), new TicketWriter(connections),
                SimpleVectorStore.builder(new CountingEmbeddingModel()).build(), gate,
                dir.resolve("does-not-exist.csv").toString(),
                dir.resolve("vectorstore.json").toString(), 100);

        svc.ingest();

        assertThat(gate.isReady()).isFalse(); // tool will report not-ready
    }
}
