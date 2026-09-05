package com.madras.jdbc;

import com.madras.MadrasReader;

/**
 * One .mdsi file per Connection. Read-only: setAutoCommit(false)/commit()/
 * rollback() throw, since there's no write/transaction concept for this
 * source. getMetaData() (java.sql.DatabaseMetaData, catalog introspection)
 * is out of scope for this pass -- see the method body for why.
 */
public final class MadrasConnection implements java.sql.Connection {

    private final MadrasReader reader;
    private boolean closed = false;

    MadrasConnection(String path) throws java.sql.SQLException {
        try {
            this.reader = new MadrasReader(path);
        } catch (RuntimeException e) {
            throw new java.sql.SQLException("Failed to open " + path, e);
        }
    }

    MadrasReader reader() {
        return reader;
    }

    @Override
    public void close() throws java.sql.SQLException {
        closed = true;
        reader.close();
    }

    @Override
    public boolean isClosed() throws java.sql.SQLException {
        return closed;
    }

    @Override
    public java.sql.Statement createStatement() throws java.sql.SQLException {
        if (closed) throw new java.sql.SQLException("Connection is closed");
        return new MadrasStatement(this);
    }

    @Override
    public java.sql.DatabaseMetaData getMetaData() throws java.sql.SQLException {
        // DatabaseMetaData is a ~150-method interface (driver/catalog
        // introspection, supported-feature flags, etc.) -- out of scope for
        // this pass. Add a real implementation if a client (e.g. a BI tool)
        // needs catalog browsing rather than just running SELECTs.
        throw new java.sql.SQLFeatureNotSupportedException("getMetaData (DatabaseMetaData)");
    }

    @Override
    public boolean isValid(int timeout) throws java.sql.SQLException {
        return !closed;
    }

    @Override
    public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws java.sql.SQLException {
        // no-op -- no warnings are ever generated
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws java.sql.SQLException {
        if (!autoCommit) {
            throw new java.sql.SQLFeatureNotSupportedException(
                "Transactions are not supported -- this is a read-only source, autoCommit is always true");
        }
    }

    @Override
    public boolean getAutoCommit() throws java.sql.SQLException {
        return true;
    }

    @Override
    public void commit() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("Transactions are not supported");
    }

    @Override
    public void rollback() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("Transactions are not supported");
    }
    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("prepareStatement");
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("prepareCall");
    }

    @Override
    public java.lang.String nativeSQL(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("nativeSQL");
    }

    @Override
    public void setReadOnly(boolean arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setReadOnly");
    }

    @Override
    public boolean isReadOnly() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("isReadOnly");
    }

    @Override
    public void setCatalog(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setCatalog");
    }

    @Override
    public java.lang.String getCatalog() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getCatalog");
    }

    @Override
    public void setTransactionIsolation(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setTransactionIsolation");
    }

    @Override
    public int getTransactionIsolation() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getTransactionIsolation");
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, int arg1, int arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("prepareStatement");
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String arg0, int arg1, int arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("prepareCall");
    }

    @Override
    public java.util.Map<java.lang.String, java.lang.Class<?>> getTypeMap() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getTypeMap");
    }

    @Override
    public void setTypeMap(java.util.Map<java.lang.String, java.lang.Class<?>> arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setTypeMap");
    }

    @Override
    public void setHoldability(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setHoldability");
    }

    @Override
    public int getHoldability() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getHoldability");
    }

    @Override
    public java.sql.Savepoint setSavepoint() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setSavepoint");
    }

    @Override
    public java.sql.Savepoint setSavepoint(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setSavepoint");
    }

    @Override
    public void releaseSavepoint(java.sql.Savepoint arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("releaseSavepoint");
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, int arg1, int arg2, int arg3) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("prepareStatement");
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String arg0, int arg1, int arg2, int arg3) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("prepareCall");
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, int arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("prepareStatement");
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, int[] arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("prepareStatement");
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String arg0, java.lang.String[] arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("prepareStatement");
    }

    @Override
    public java.sql.Clob createClob() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("createClob");
    }

    @Override
    public java.sql.Blob createBlob() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("createBlob");
    }

    @Override
    public java.sql.NClob createNClob() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("createNClob");
    }

    @Override
    public java.sql.SQLXML createSQLXML() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("createSQLXML");
    }

    @Override
    public void setClientInfo(java.lang.String arg0, java.lang.String arg1) throws java.sql.SQLClientInfoException {
        throw new java.sql.SQLClientInfoException("setClientInfo not supported", new java.util.HashMap<>());
    }

    @Override
    public void setClientInfo(java.util.Properties arg0) throws java.sql.SQLClientInfoException {
        throw new java.sql.SQLClientInfoException("setClientInfo not supported", new java.util.HashMap<>());
    }

    @Override
    public java.lang.String getClientInfo(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getClientInfo");
    }

    @Override
    public java.util.Properties getClientInfo() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getClientInfo");
    }

    @Override
    public java.sql.Array createArrayOf(java.lang.String arg0, java.lang.Object[] arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("createArrayOf");
    }

    @Override
    public java.sql.Struct createStruct(java.lang.String arg0, java.lang.Object[] arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("createStruct");
    }

    @Override
    public void setSchema(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setSchema");
    }

    @Override
    public java.lang.String getSchema() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getSchema");
    }

    @Override
    public void abort(java.util.concurrent.Executor arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("abort");
    }

    @Override
    public void setNetworkTimeout(java.util.concurrent.Executor arg0, int arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setNetworkTimeout");
    }

    @Override
    public int getNetworkTimeout() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getNetworkTimeout");
    }

    @Override
    public void beginRequest() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("beginRequest");
    }

    @Override
    public void endRequest() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("endRequest");
    }

    @Override
    public boolean setShardingKeyIfValid(java.sql.ShardingKey arg0, java.sql.ShardingKey arg1, int arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setShardingKeyIfValid");
    }

    @Override
    public boolean setShardingKeyIfValid(java.sql.ShardingKey arg0, int arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setShardingKeyIfValid");
    }

    @Override
    public void setShardingKey(java.sql.ShardingKey arg0, java.sql.ShardingKey arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setShardingKey");
    }

    @Override
    public void setShardingKey(java.sql.ShardingKey arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setShardingKey");
    }


    @Override
    public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("createStatement(int,int)");
    }

    @Override
    public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("createStatement(int,int,int)");
    }

    @Override
    public void rollback(java.sql.Savepoint savepoint) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("rollback(Savepoint)");
    }

    @Override
    public <T> T unwrap(java.lang.Class<T> iface) throws java.sql.SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new java.sql.SQLException("Not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(java.lang.Class<?> iface) throws java.sql.SQLException {
        return iface.isInstance(this);
    }
}
