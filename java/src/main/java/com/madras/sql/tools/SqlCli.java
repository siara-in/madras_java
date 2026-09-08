package com.madras.sql.tools;

import java.sql.*;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Interactive CLI for testing the madras-sql JDBC driver directly.
 *
 * Usage:
 *   java -cp <jar> com.madras.sql.tools.SqlCli jdbc:madras-sql:/path/to/file.mdsi
 *   java -cp <jar> com.madras.sql.tools.SqlCli jdbc:madras-sql:/path/to/file.mdsi "SELECT COUNT(*) FROM t"
 *
 * With no query argument, connects once and reads queries interactively,
 * with readline-style line editing and persistent history (JLine --
 * true GNU readline isn't directly usable from Java). Meta-commands
 * (\dt, \d, \?) mirror psql's convention for browsing schema metadata via
 * the JDBC driver's own DatabaseMetaData implementation, rather than
 * routing through the SQL parser.
 */
public final class SqlCli {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: SqlCli <jdbc-url> [\"<sql>\"]");
            System.exit(1);
        }
        String url = args[0];

        System.out.println("Connecting: " + url);
        long connectStart = System.nanoTime();
        Connection conn = DriverManager.getConnection(url);
        System.out.printf("Connected in %.1f ms%n%n", (System.nanoTime() - connectStart) / 1e6);

        Statement stmt = conn.createStatement();

        if (args.length >= 2) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (i > 1) sb.append(' ');
                sb.append(args[i]);
            }
            runQuery(stmt, sb.toString());
            conn.close();
            return;
        }

        System.out.println("Interactive mode -- 'exit' to quit, '.?' for meta-commands:");

        Terminal terminal = TerminalBuilder.builder().system(true).build();
        Path historyFile = Paths.get(System.getProperty("user.home"), ".madras_sql_history");
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .variable(LineReader.HISTORY_FILE, historyFile)
                .build();

        while (true) {
            String line;
            try {
                line = reader.readLine("sql> ");
            } catch (UserInterruptException e) {
                continue; // Ctrl-C: discard the current line, prompt again
            } catch (EndOfFileException e) {
                break; // Ctrl-D
            }
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) break;
            if (line.endsWith(";")) line = line.substring(0, line.length() - 1).trim();

            if (line.startsWith(".")) {
                runMetaCommand(conn, line);
                continue;
            }
            runQuery(stmt, line);
        }
        if (currentPrepared != null) currentPrepared.close();
        conn.close();
        System.out.println("Disconnected.");
    }

    // Holds the statement created by .prepare, for .exec to bind and run
    // repeatedly -- a manual, step-by-step alternative to .compare when
    // you want to drive prepare/bind/execute yourself rather than have
    // the harness cycle through generated values automatically.
    private static PreparedStatement currentPrepared;

    private static void runMetaCommand(Connection conn, String cmd) {
        try {
            DatabaseMetaData md = conn.getMetaData();
            if (cmd.equals(".?")) {
                System.out.println("  .dt          list tables");
                System.out.println("  .d <table>   describe a table's columns");
                System.out.println("  .d           describe the default table ('t')");
                System.out.println("  .compare <n> <base> <sql with one ?>");
                System.out.println("               compare Statement (re-parse each call) vs");
                System.out.println("               PreparedStatement (prepared once) over n calls,");
                System.out.println("               binding base..base+9 cyclically");
                System.out.println("  .prepare <sql with ?>");
                System.out.println("               prepare a statement, held for .exec");
                System.out.println("  .exec <param1> <param2> ...");
                System.out.println("               bind and run the .prepare'd statement");
                System.out.println("  .log on|off  toggle \"Using index: ...\" diagnostic logging (default on)");
                System.out.println("  exit / quit  disconnect and exit");
                return;
            }
            if (cmd.startsWith(".log")) {
                String rest = cmd.length() > 4 ? cmd.substring(4).trim() : "";
                if (rest.equals("on")) {
                    com.madras.sql.MadrasSqlEngine.setIndexLogging(true);
                    System.out.println("index logging: on");
                } else if (rest.equals("off")) {
                    com.madras.sql.MadrasSqlEngine.setIndexLogging(false);
                    System.out.println("index logging: off");
                } else {
                    System.out.println("index logging is currently " +
                            (com.madras.sql.MadrasSqlEngine.getIndexLogging() ? "on" : "off") +
                            " -- usage: .log on|off");
                }
                return;
            }
            if (cmd.startsWith(".compare ")) {
                String rest = cmd.substring(9).trim();
                String[] parts = rest.split("\\s+", 3);
                if (parts.length < 3) {
                    System.out.println("usage: .compare <n> <base> <sql with one ?>");
                    return;
                }
                int n = Integer.parseInt(parts[0]);
                long base = Long.parseLong(parts[1]);
                String sql = parts[2];
                runCompare(conn, sql, n, base);
                return;
            }
            if (cmd.startsWith(".prepare ")) {
                String sql = cmd.substring(9).trim();
                if (sql.isEmpty()) {
                    System.out.println("usage: .prepare <sql with ?>");
                    return;
                }
                if (currentPrepared != null) currentPrepared.close();
                long t0 = System.nanoTime();
                currentPrepared = conn.prepareStatement(sql);
                double ms = (System.nanoTime() - t0) / 1e6;
                ParameterMetaData pmd = null;
                int paramCount;
                try {
                    pmd = currentPrepared.getParameterMetaData();
                    paramCount = pmd.getParameterCount();
                } catch (SQLFeatureNotSupportedException e) {
                    // getParameterMetaData isn't implemented on this driver --
                    // count '?' directly instead, just for the echoed message.
                    paramCount = 0;
                    boolean inStr = false;
                    for (char c : sql.toCharArray()) {
                        if (c == '\'') inStr = !inStr;
                        else if (c == '?' && !inStr) paramCount++;
                    }
                }
                System.out.printf("prepared (%.2f ms), %d parameter(s) -- use .exec to run it%n", ms, paramCount);
                return;
            }
            if (cmd.startsWith(".exec")) {
                if (currentPrepared == null) {
                    System.out.println("no statement prepared -- use .prepare <sql with ?> first");
                    return;
                }
                String rest = cmd.length() > 5 ? cmd.substring(5).trim() : "";
                String[] params = rest.isEmpty() ? new String[0] : rest.split("\\s+");
                for (int i = 0; i < params.length; i++) {
                    currentPrepared.setString(i + 1, params[i]);
                }
                runPreparedQuery(currentPrepared);
                return;
            }
            if (cmd.equals(".dt")) {
                printResultSet(md.getTables(null, null, "%", null), new String[]{"TABLE_NAME", "TABLE_TYPE"});
                return;
            }
            if (cmd.equals(".d") || cmd.startsWith(".d ")) {
                String table = cmd.equals(".d") ? "t" : cmd.substring(3).trim();
                printResultSet(md.getColumns(null, null, table, "%"),
                        new String[]{"COLUMN_NAME", "TYPE_NAME", "COLUMN_SIZE", "IS_NULLABLE"});
                return;
            }
            System.out.println("Unknown meta-command: " + cmd + " (try .?)");
        } catch (SQLException e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }

    // Runs an already-bound PreparedStatement (used by .exec), printing
    // the same timing/preview format as runQuery() below.
    private static void runPreparedQuery(PreparedStatement ps) {
        long t0 = System.nanoTime();
        try {
            ResultSet rs = ps.executeQuery();
            double execMs = (System.nanoTime() - t0) / 1e6;
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            long rowCount = 0;
            final int previewLimit = 10;
            while (rs.next()) {
                if (rowCount < previewLimit) {
                    StringBuilder row = new StringBuilder("  ");
                    for (int c = 1; c <= cols; c++) {
                        if (c > 1) row.append("\t");
                        row.append(rs.getString(c));
                    }
                    System.out.println(row);
                } else if (rowCount == previewLimit) {
                    System.out.println("  ... (more rows follow, not printed)");
                }
                rowCount++;
            }
            double totalMs = (System.nanoTime() - t0) / 1e6;
            System.out.printf("rows=%d  executeQuery=%.1fms  total=%.1fms%n%n", rowCount, execMs, totalMs);
        } catch (SQLException e) {
            double ms = (System.nanoTime() - t0) / 1e6;
            System.out.println("ERROR (" + ms + " ms): " + e.getMessage());
            System.out.println();
        }
    }

    // Runs the same query N times two ways -- once substituting a literal
    // value into fresh SQL text each call (Statement, always re-parses
    // and re-plans), and once binding a PreparedStatement prepared ONCE
    // up front (skips re-parsing/re-planning on every call) -- so the
    // performance difference can be seen directly rather than taken on
    // faith. The bound value cycles across a small range (base..base+9)
    // on each iteration, matching how the underlying engine's own C++
    // benchmark was measured, so this isn't just re-hitting the exact
    // same single value.
    private static void runCompare(Connection conn, String sqlWithPlaceholder, int iterations, long base) {
        // Suppresses "Using index: ..." logging for the duration of the
        // timed loops -- otherwise this fires on every one of potentially
        // thousands of iterations, which is both unreadable noise and
        // real stderr I/O overhead that would visibly skew the very
        // timing being measured. Restores whatever state was in effect
        // before, not hardcoded back to "on" -- respects .log if the
        // user had already turned it off themselves.
        boolean wasEnabled = com.madras.sql.MadrasSqlEngine.getIndexLogging();
        com.madras.sql.MadrasSqlEngine.setIndexLogging(false);
        try {
            Statement stmt = conn.createStatement();
            long t0 = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                String literalSql = sqlWithPlaceholder.replace("?", String.valueOf(base + (i % 10)));
                ResultSet rs = stmt.executeQuery(literalSql);
                while (rs.next()) { /* drain */ }
            }
            double literalMs = (System.nanoTime() - t0) / 1e6;

            PreparedStatement ps = conn.prepareStatement(sqlWithPlaceholder);
            t0 = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                ps.setLong(1, base + (i % 10));
                ResultSet rs = ps.executeQuery();
                while (rs.next()) { /* drain */ }
            }
            double preparedMs = (System.nanoTime() - t0) / 1e6;
            ps.close();

            System.out.printf("Statement (re-parse each call):    %8.1f ms  (%.4f ms/call)%n",
                    literalMs, literalMs / iterations);
            System.out.printf("PreparedStatement (prepared once): %8.1f ms  (%.4f ms/call)%n",
                    preparedMs, preparedMs / iterations);
            System.out.printf("speedup: %.2fx%n%n", literalMs / preparedMs);
        } catch (SQLException e) {
            System.out.println("ERROR: " + e.getMessage());
        } finally {
            com.madras.sql.MadrasSqlEngine.setIndexLogging(wasEnabled);
        }
    }

    // Prints only the named columns from a metadata ResultSet, in a
    // simple aligned table -- the full JDBC metadata column set (20+
    // columns for getColumns()) is far more than useful to eyeball
    // directly.
    private static void printResultSet(ResultSet rs, String[] displayCols) throws SQLException {
        int[] widths = new int[displayCols.length];
        for (int i = 0; i < displayCols.length; i++) widths[i] = displayCols[i].length();

        java.util.List<String[]> rows = new java.util.ArrayList<>();
        while (rs.next()) {
            String[] row = new String[displayCols.length];
            for (int i = 0; i < displayCols.length; i++) {
                String v = rs.getString(displayCols[i]);
                row[i] = v == null ? "" : v;
                widths[i] = Math.max(widths[i], row[i].length());
            }
            rows.add(row);
        }
        rs.close();

        StringBuilder header = new StringBuilder();
        for (int i = 0; i < displayCols.length; i++) {
            header.append(pad(displayCols[i], widths[i])).append("  ");
        }
        System.out.println(header);
        StringBuilder sep = new StringBuilder();
        for (int w : widths) { for (int i = 0; i < w; i++) sep.append('-'); sep.append("  "); }
        System.out.println(sep);

        if (rows.isEmpty()) {
            System.out.println("(no rows)");
        }
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.length; i++) line.append(pad(row[i], widths[i])).append("  ");
            System.out.println(line);
        }
        System.out.println();
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static void runQuery(Statement stmt, String sql) {
        System.out.println("-- " + sql);
        long t0 = System.nanoTime();
        try {
            ResultSet rs = stmt.executeQuery(sql);
            double execMs = (System.nanoTime() - t0) / 1e6;

            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();

            long t1 = System.nanoTime();
            long rowCount = 0;
            final int previewLimit = 10;
            while (rs.next()) {
                if (rowCount < previewLimit) {
                    StringBuilder row = new StringBuilder("  ");
                    for (int c = 1; c <= cols; c++) {
                        if (c > 1) row.append("\t");
                        row.append(rs.getString(c));
                    }
                    System.out.println(row);
                } else if (rowCount == previewLimit) {
                    System.out.println("  ... (more rows follow, not printed)");
                }
                rowCount++;
            }
            double fetchMs = (System.nanoTime() - t1) / 1e6;
            double totalMs = (System.nanoTime() - t0) / 1e6;

            System.out.printf("rows=%d  executeQuery=%.1fms  fetch/iterate=%.1fms  total=%.1fms%n%n",
                    rowCount, execMs, fetchMs, totalMs);
        } catch (SQLException e) {
            double ms = (System.nanoTime() - t0) / 1e6;
            System.out.println("ERROR (" + ms + " ms): " + e.getMessage());
            System.out.println();
        }
    }
}
