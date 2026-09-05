package com.madras.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC URL format: jdbc:madras:/absolute/path/to/file.mdsi
 *
 * One .mdsi file per Connection -- there's no server/multi-database concept
 * here, so the "database name" in the URL is just the file path.
 */
public final class MadrasDriver implements Driver {

    private static final String URL_PREFIX = "jdbc:madras:";

    static {
        try {
            DriverManager.registerDriver(new MadrasDriver());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register MadrasDriver", e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        String path = url.substring(URL_PREFIX.length());
        return new MadrasConnection(path);
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
    public int getMajorVersion() {
        return 0;
    }

    @Override
    public int getMinorVersion() {
        return 1;
    }

    @Override
    public boolean jdbcCompliant() {
        // Not fully JDBC compliant -- see MadrasConnection/MadrasResultSet for
        // the explicit list of unsupported features (transactions, updatable
        // result sets, full SQL grammar).
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging not supported");
    }
}
