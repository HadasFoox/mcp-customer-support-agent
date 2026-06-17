package com.cheq.support.ingest;

import com.cheq.support.model.Ticket;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTicketParserTest {

    private final CsvTicketParser parser = new CsvTicketParser();

    private static final String CSV = """
            subject,body,answer,type,queue,priority,language,version,tag_1,tag_2,tag_3,tag_4,tag_5
            Login fails,Cannot log in,Reset password,Incident,Technical Support,high,en,120,auth,,,,
            Rechnung falsch,Betrag stimmt nicht,Korrigiert,Request,Billing and Payments,low,de,,billing,refund,,,
            ログイン,できない,対応しました,Incident,Technical Support,medium,ja,200,auth,ui,,,
            """;

    @Test
    void mapsColumnsByHeaderAndSynthesizesRowIndexId() {
        List<Ticket> tickets = parser.parse(new StringReader(CSV), 0); // no cap

        assertThat(tickets).hasSize(3);

        Ticket first = tickets.get(0);
        assertThat(first.ticketId()).isZero();
        assertThat(first.subject()).isEqualTo("Login fails");
        assertThat(first.body()).isEqualTo("Cannot log in");
        assertThat(first.answer()).isEqualTo("Reset password");
        assertThat(first.queue()).isEqualTo("Technical Support");
        assertThat(first.language()).isEqualTo("en");
        assertThat(first.version()).isEqualTo(120);
        assertThat(first.tag1()).isEqualTo("auth");
        assertThat(first.tag2()).isNull(); // blank cell → null

        assertThat(tickets.get(2).ticketId()).isEqualTo(2);
        assertThat(tickets.get(2).language()).isEqualTo("ja"); // multilingual preserved
    }

    @Test
    void nonNumericOrBlankVersionBecomesNull() {
        List<Ticket> tickets = parser.parse(new StringReader(CSV), 0);
        assertThat(tickets.get(1).version()).isNull(); // blank version
    }

    @Test
    void respectsRowCap() {
        List<Ticket> tickets = parser.parse(new StringReader(CSV), 2);
        assertThat(tickets).hasSize(2);
        assertThat(tickets).extracting(Ticket::ticketId).containsExactly(0L, 1L);
    }
}
