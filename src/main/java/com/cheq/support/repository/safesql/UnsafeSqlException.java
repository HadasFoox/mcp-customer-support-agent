package com.cheq.support.repository.safesql;

/** Thrown by {@link SqlQueryFirewall} when candidate SQL violates a safety rule. */
public class UnsafeSqlException extends RuntimeException {

    public UnsafeSqlException(String message) {
        super(message);
    }
}
