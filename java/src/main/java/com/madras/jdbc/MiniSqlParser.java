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
 *   [WHERE <condition>]
 *   [LIMIT n]
 *
 * <condition> is exactly one of:
 *   col = value
 *   col IN (v1, v2, ...)
 *   col BETWEEN v1 AND v2
 *   col > value | col >= value | col < value | col <= value
 *
 * NOT supported: JOIN, GROUP BY, ORDER BY, aggregates, subqueries, multiple
 * ANDed/ORed conditions, operators other than the above, INSERT/UPDATE/
 * DELETE/DDL.
 */
final class MiniSqlParser {

    enum Op { EQ, IN, BETWEEN, GT, GTE, LT, LTE }

    static final class ParsedQuery {
        List<String> columns;      // empty list means SELECT *
        String whereColumn;        // null if no WHERE
        Op whereOp;
        List<String> whereValues;  // 1 value for EQ/GT/GTE/LT/LTE, 2 for BETWEEN, N for IN
        Long limit;                 // null if no LIMIT
    }

    private static final Pattern OUTER_PATTERN = Pattern.compile(
            "^\\s*SELECT\\s+(.+?)\\s+FROM\\s+\\S+\\s*(?:WHERE\\s+(.+?))?\\s*(?:LIMIT\\s+(\\d+))?\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern EQ_PATTERN = Pattern.compile(
            "^(\\w+)\\s*=\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_PATTERN = Pattern.compile(
            "^(\\w+)\\s+IN\\s*\\((.+)\\)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BETWEEN_PATTERN = Pattern.compile(
            "^(\\w+)\\s+BETWEEN\\s+(.+?)\\s+AND\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CMP_PATTERN = Pattern.compile(
            "^(\\w+)\\s*(>=|<=|>|<)\\s*(.+)$");

    private MiniSqlParser() {}

    static ParsedQuery parse(String sql) throws SQLException {
        Matcher m = OUTER_PATTERN.matcher(sql.trim());
        if (!m.matches()) {
            throw new SQLException(
                "Unsupported SQL (expected 'SELECT cols|* FROM x [WHERE condition] [LIMIT n]'): " + sql);
        }
        ParsedQuery q = new ParsedQuery();

        String colsPart = m.group(1).trim();
        q.columns = new ArrayList<>();
        if (!colsPart.equals("*")) {
            for (String c : colsPart.split(",")) q.columns.add(c.trim());
        }

        String wherePart = m.group(2);
        if (wherePart != null) {
            parseCondition(wherePart.trim(), q);
        }

        String limitStr = m.group(3);
        q.limit = limitStr == null ? null : Long.parseLong(limitStr);

        return q;
    }

    private static void parseCondition(String cond, ParsedQuery q) throws SQLException {
        Matcher m;

        m = IN_PATTERN.matcher(cond);
        if (m.matches()) {
            q.whereColumn = m.group(1);
            q.whereOp = Op.IN;
            q.whereValues = new ArrayList<>();
            for (String v : m.group(2).split(",")) {
                q.whereValues.add(unquote(v.trim()));
            }
            return;
        }

        m = BETWEEN_PATTERN.matcher(cond);
        if (m.matches()) {
            q.whereColumn = m.group(1);
            q.whereOp = Op.BETWEEN;
            q.whereValues = new ArrayList<>();
            q.whereValues.add(unquote(m.group(2).trim()));
            q.whereValues.add(unquote(m.group(3).trim()));
            return;
        }

        m = CMP_PATTERN.matcher(cond);
        if (m.matches()) {
            q.whereColumn = m.group(1);
            String op = m.group(2);
            q.whereOp = op.equals(">") ? Op.GT : op.equals(">=") ? Op.GTE : op.equals("<") ? Op.LT : Op.LTE;
            q.whereValues = new ArrayList<>();
            q.whereValues.add(unquote(m.group(3).trim()));
            return;
        }

        m = EQ_PATTERN.matcher(cond);
        if (m.matches()) {
            q.whereColumn = m.group(1);
            q.whereOp = Op.EQ;
            q.whereValues = new ArrayList<>();
            q.whereValues.add(unquote(m.group(2).trim()));
            return;
        }

        throw new SQLException("Unsupported WHERE condition (see MiniSqlParser for the supported subset): " + cond);
    }

    private static String unquote(String raw) {
        if ((raw.startsWith("'") && raw.endsWith("'")) || (raw.startsWith("\"") && raw.endsWith("\""))) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }
}

