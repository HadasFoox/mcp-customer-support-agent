package com.cheq.support.ingest;

import com.cheq.support.model.Ticket;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the {@code Tobi-Bueck/customer-support-tickets} CSV into {@link Ticket} records.
 *
 * <p>Columns are read <b>by header name</b> (order-independent, and tolerant of extra columns).
 * {@code ticket_id} is synthesized as the 0-based row index — stable given a fixed read order
 * and row cap, so re-ingestion and vector metadata stay aligned. A missing or blank cell maps
 * to {@code null}; a non-numeric {@code version} maps to {@code null}.
 */
@Component
public class CsvTicketParser {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()                  // first record defines the headers
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .get();

    /** Parse up to {@code maxRows} tickets (use a non-positive value for "no cap"). */
    public List<Ticket> parse(Reader reader, int maxRows) {
        List<Ticket> tickets = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(reader, FORMAT)) {
            long id = 0;
            for (CSVRecord record : parser) {
                if (maxRows > 0 && id >= maxRows) {
                    break;
                }
                tickets.add(toTicket(id, record));
                id++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse dataset CSV", e);
        }
        return tickets;
    }

    private static Ticket toTicket(long id, CSVRecord r) {
        return new Ticket(
                id,
                get(r, "subject"),
                get(r, "body"),
                get(r, "answer"),
                get(r, "type"),
                get(r, "queue"),
                get(r, "priority"),
                get(r, "language"),
                parseIntOrNull(get(r, "version")),
                get(r, "tag_1"),
                get(r, "tag_2"),
                get(r, "tag_3"),
                get(r, "tag_4"),
                get(r, "tag_5")
        );
    }

    /** Null-safe cell read by header name; missing header or blank value → null. */
    private static String get(CSVRecord r, String column) {
        if (!r.isMapped(column)) {
            return null;
        }
        String value = r.get(column);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
