package com.cheq.support.repository.safesql;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Java-enforced safety gate for LLM-generated SQL. Never trusts the prompt — every candidate
 * query passes through {@link #sanitize(String)} before it can reach the read-only connection.
 *
 * <p>Rules:
 * <ol>
 *   <li><b>Comment stripping</b> — line ({@code --}) and block ({@code /* *}{@code /}) comments are
 *       removed (string-literal aware) so they can't hide a second statement or comment out the cap.</li>
 *   <li><b>No statement stacking</b> — a single trailing {@code ;} is stripped; any remaining
 *       top-level {@code ;} (one outside a string literal) is rejected.</li>
 *   <li><b>SELECT-only</b> — the statement must begin with {@code SELECT}.</li>
 *   <li><b>Hard row cap</b> — the query is wrapped as {@code SELECT * FROM (<query>) LIMIT 50}.
 *       This caps any larger or absent inner limit to 50, while a smaller inner {@code LIMIT}
 *       still bounds rows first ("cap down only"), and is immune to inner-limit / comment tricks.</li>
 * </ol>
 */
@Component
public class SqlQueryFirewall {

    static final int MAX_ROWS = 50;

    private static final Pattern STARTS_WITH_SELECT =
            Pattern.compile("^\\s*SELECT\\b", Pattern.CASE_INSENSITIVE);

    /**
     * @return a safe, row-capped SELECT ready to execute on a read-only connection
     * @throws UnsafeSqlException if the candidate violates any rule
     */
    public String sanitize(String candidateSql) {
        if (candidateSql == null || candidateSql.isBlank()) {
            throw new UnsafeSqlException("Empty SQL");
        }

        String noComments = stripComments(candidateSql).trim();
        String stripped = stripSingleTrailingSemicolon(noComments).trim();

        if (stripped.isBlank()) {
            throw new UnsafeSqlException("Empty SQL after sanitization");
        }
        if (containsTopLevelSemicolon(stripped)) {
            throw new UnsafeSqlException("Statement stacking is not allowed");
        }
        if (!STARTS_WITH_SELECT.matcher(stripped).find()) {
            throw new UnsafeSqlException("Only SELECT statements are permitted");
        }

        return "SELECT * FROM (" + stripped + ") LIMIT " + MAX_ROWS;
    }

    /** Remove line and block comments, leaving string literals untouched. */
    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        boolean inStr = false;
        while (i < n) {
            char c = sql.charAt(i);
            if (inStr) {
                out.append(c);
                if (c == '\'') {
                    if (i + 1 < n && sql.charAt(i + 1) == '\'') { // escaped '' inside literal
                        out.append('\'');
                        i += 2;
                        continue;
                    }
                    inStr = false;
                }
                i++;
            } else if (c == '\'') {
                inStr = true;
                out.append(c);
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2; // skip the closing */
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String stripSingleTrailingSemicolon(String sql) {
        String s = sql.stripTrailing();
        return s.endsWith(";") ? s.substring(0, s.length() - 1) : s;
    }

    /** True if a {@code ;} appears outside a string literal (i.e. statement stacking). */
    private static boolean containsTopLevelSemicolon(String sql) {
        int n = sql.length();
        boolean inStr = false;
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (inStr) {
                if (c == '\'') {
                    if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                        i++; // skip escaped ''
                    } else {
                        inStr = false;
                    }
                }
            } else if (c == '\'') {
                inStr = true;
            } else if (c == ';') {
                return true;
            }
        }
        return false;
    }
}
