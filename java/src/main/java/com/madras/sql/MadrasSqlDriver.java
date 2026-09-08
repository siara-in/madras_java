package com.madras.sql;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC URL format: jdbc:madras-sql:/absolute/path/to/file.mdsi
 *
 * Backed by the new native madras_sql engine (com.madras.sql.MadrasSqlEngine
 * -> JNI -> src/madras_sql/src/madras_sql_engine.hpp), NOT Calcite. SQL text
 * is parsed and executed entirely natively -- no java.sql.Connection
 * delegation to an embedded database the way com.madras.calcite works.
 *
 * SQL support is intentionally narrow right now (matches the engine's
 * current scope): SELECT cols|* FROM t [WHERE col OP literal] [LIMIT n],
 * and COUNT(*) (answered in O(1) from trie metadata when unfiltered, or
 * from a pushed index lookup's match count when filtered). No GROUP BY,
 * ORDER BY, joins, or most functions yet -- unsupported SQL throws a clear
 * SQLException naming what wasn't understood, rather than silently
 * misinterpreting it. Use jdbc:madras-calcite: for full ANSI SQL.
 */
public final class MadrasSqlDriver implements Driver {

    private static final String URL_PREFIX = "jdbc:madras-sql:";

    static {
        try {
            DriverManager.registerDriver(new MadrasSqlDriver());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register MadrasSqlDriver", e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        String path = url.substring(URL_PREFIX.length());
        return new MadrasSqlConnection(path);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() { return 0; }

    @Override
    public int getMinorVersion() { return 1; }

    @Override
    public boolean jdbcCompliant() { return false; }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging not supported");
    }
}
