package com.cheq.support.model;

/**
 * One customer-support ticket, mapped from the
 * {@code Tobi-Bueck/customer-support-tickets} dataset.
 *
 * <p>The dataset has no native identifier, so {@code ticketId} is synthesized as the
 * stable 0-based CSV row index (deterministic given a fixed read order + row cap),
 * which lets vector-store metadata and SQL rows reference the same key.
 *
 * <p>{@code version} is nullable because the source column, while usually an integer,
 * can be blank or non-numeric on some rows.
 */
public record Ticket(
        long ticketId,
        String subject,
        String body,
        String answer,
        String type,
        String queue,
        String priority,
        String language,
        Integer version,
        String tag1,
        String tag2,
        String tag3,
        String tag4,
        String tag5
) {
}
