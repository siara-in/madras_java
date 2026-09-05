package com.madras.jdbc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a small, explicit SELECT subset:
 *
 *   SELECT col1, col2, ... | *
 *   FROM <anything -- ignored, single .mdsi file per connection>
 *   [WHERE col = 'value' | col = 123]
 *   [LIMIT n]
 *
 * NOT supported (throws SQLException naming the SQL so the caller knows
 * exactly what wasn't understood, rather than silently misinterpreting it):
 * JOIN, GROUP BY, ORDER BY, aggregates, subqueries, multi-condition WHERE,
 * operators other than '=', INSERT/UPDATE/DELETE/DDL.
 */
final class MiniSqlParser {

    static final class ParsedQuery {
        List<String> columns;      // empty list means SELECT *
        String whereColumn;        // null if no WHERE
        String whereValue;         // raw literal text (quotes stripped)
        Long limit;                 // null if no LIMIT
    }

    private static final Pattern SELECT_PATTERN = Pattern.compile(
            "^\\s*SELECT\\s+(.+?)\\s+FROM\\s+\\S+\\s*(?:WHERE\\s+(\\w+)\\s*=\\s*('[^']*'|\"[^\"]*\"|[\\w.\\-]+))?\\s*(?:LIMIT\\s+(\\d+))?\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private MiniSqlParser() {}

    static ParsedQuery parse(String sql) throws SQLException {
        Matcher m = SELECT_PATTERN.matcher(sql.trim());
        if (!m.matches()) {
            throw new SQLException(
                "Unsupported SQL (only 'SELECT cols|* FROM x [WHERE col = value] [LIMIT n]' is supported): "
                + sql);
        }
        ParsedQuery q = new ParsedQuery();

        String colsPart = m.group(1).trim();
        if (colsPart.equals("*")) {
            q.columns = new ArrayList<>();
        } else {
            q.columns = new ArrayList<>();
            for (String c : colsPart.split(",")) {
                q.columns.add(c.trim());
            }
        }

        q.whereColumn = m.group(2);
        String rawValue = m.group(3);
        if (rawValue != null) {
            if ((rawValue.startsWith("'") && rawValue.endsWith("'")) ||
                (rawValue.startsWith("\"") && rawValue.endsWith("\""))) {
                q.whereValue = rawValue.substring(1, rawValue.length() - 1);
            } else {
                q.whereValue = rawValue;
            }
        }

        String limitStr = m.group(4);
        q.limit = limitStr == null ? null : Long.parseLong(limitStr);

        return q;
    }
}
