package com.madras.jdbc;

import com.madras.MadrasReader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Real (not stubbed) DatabaseMetaData: JDBC client tools (DBeaver, DataGrip,
 * SQuirreL, etc.) call this routinely just to connect and browse schemas --
 * throwing SQLFeatureNotSupportedException here (as an earlier pass of this
 * driver did) breaks basic connectivity, unlike the rare Connection/
 * Statement/ResultSet methods where that's a reasonable scope boundary.
 *
 * One .mdsi file is treated as exactly one table (name = the file's stem),
 * with no catalog/schema concept -- getCatalogs()/getSchemas() return empty
 * result sets, and getTables()/getColumns()/getPrimaryKeys() always
 * describe that single implicit table regardless of the catalog/schema/
 * table-name-pattern arguments passed in (a real multi-file catalog isn't
 * something this single-file connection has any way to enumerate).
 *
 * Every other method (the ~150 supportsXXX/getMaxXXX/etc. capability
 * queries, and the ResultSet-returning methods not central to basic browse
 * -- getProcedures, getIndexInfo, getImportedKeys, getUDTs, etc.) returns a
 * safe default (false/0/null/empty ResultSet) via generated stubs -- see
 * DatabaseMetaData.stubs.filtered.txt's generation in the build notes.
 */
public final class MadrasDatabaseMetaData implements java.sql.DatabaseMetaData {

    private final MadrasConnection connection;
    private final MadrasReader reader;
    private final String path;
    private final String tableName;

    MadrasDatabaseMetaData(MadrasConnection connection, MadrasReader reader, String path) {
        this.connection = connection;
        this.reader = reader;
        this.path = path;
        String name = new java.io.File(path).getName();
        int dot = name.lastIndexOf('.');
        this.tableName = dot == -1 ? name : name.substring(0, dot);
    }

    private ResultSet emptyResultSet() {
        return buildResultSet(new String[]{"X"}, new char[]{'t'}, new Object[0][]);
    }

    private static ResultSet buildResultSet(String[] colNames, char[] mstTypes, Object[][] rows) {
        MadrasResultSetMetaData md = new MadrasResultSetMetaData(colNames, mstTypes);
        long[] rowIds = new long[rows.length];
        for (int i = 0; i < rowIds.length; i++) rowIds[i] = i;
        return new MadrasResultSet(colNames, rows, rowIds, md);
    }

    // ---------------------------------------------------------------
    // Product / driver info
    // ---------------------------------------------------------------

    @Override public String getDatabaseProductName() { return "madras"; }
    @Override public String getDatabaseProductVersion() { return "0.1.0"; }
    @Override public String getDriverName() { return "madras JDBC Driver"; }
    @Override public String getDriverVersion() { return "0.1.0"; }
    @Override public int getDriverMajorVersion() { return 0; }
    @Override public int getDriverMinorVersion() { return 1; }
    @Override public int getJDBCMajorVersion() { return 4; }
    @Override public int getJDBCMinorVersion() { return 2; }
    @Override public String getURL() { return "jdbc:madras:" + path; }
    @Override public String getUserName() { return null; }
    @Override public boolean isReadOnly() { return true; }
    @Override public Connection getConnection() { return connection; }
    @Override public String getIdentifierQuoteString() { return "\""; }
    @Override public String getSearchStringEscape() { return "\\"; }
    @Override public String getExtraNameCharacters() { return ""; }
    @Override public String getSQLKeywords() { return ""; }
    @Override public String getNumericFunctions() { return ""; }
    @Override public String getStringFunctions() { return ""; }
    @Override public String getSystemFunctions() { return ""; }
    @Override public String getTimeDateFunctions() { return ""; }
    @Override public boolean supportsTransactions() { return false; }
    @Override public int getDefaultTransactionIsolation() { return Connection.TRANSACTION_NONE; }
    @Override public boolean supportsResultSetType(int type) { return type == ResultSet.TYPE_FORWARD_ONLY; }
    @Override public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    // ---------------------------------------------------------------
    // Catalog / schema browsing
    // ---------------------------------------------------------------

    @Override
    public ResultSet getCatalogs() {
        return buildResultSet(new String[]{"TABLE_CAT"}, new char[]{'t'}, new Object[0][]);
    }

    @Override
    public ResultSet getSchemas() {
        return buildResultSet(new String[]{"TABLE_SCHEM", "TABLE_CATALOG"}, new char[]{'t', 't'}, new Object[0][]);
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) {
        return getSchemas();
    }

    @Override
    public ResultSet getTableTypes() {
        return buildResultSet(new String[]{"TABLE_TYPE"}, new char[]{'t'}, new Object[][]{{"TABLE"}});
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) {
        String[] cols = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
                "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"};
        char[] mst = {'t', 't', 't', 't', 't', 't', 't', 't', 't', 't'};
        Object[][] rows = {{null, null, tableName, "TABLE", null, null, null, null, null, null}};
        return buildResultSet(cols, mst, rows);
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) {
        String[] cols = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
                "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS",
                "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION",
                "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE",
                "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN"};
        char[] mst = {'t', 't', 't', 't', 'i', 't', 'i', 'i', 'i', 'i', 'i', 't',
                't', 'i', 'i', 'i', 'i', 't', 't', 't', 't', 'i', 't', 't'};

        List<Object[]> rows = new ArrayList<>();
        int ordinal = 1;
        for (MadrasReader.ColumnMeta c : reader.metadata().columns) {
            if (c.type == 'S') break; // secondary-index columns aren't user-facing data columns
            if (columnNamePattern != null && !columnNamePattern.equals("%") && !c.name.equals(columnNamePattern)) {
                ordinal++;
                continue;
            }
            int sqlType = sqlTypeFor(c.type);
            String typeName = typeNameFor(c.type);
            rows.add(new Object[]{
                    null, null, tableName, c.name, sqlType, typeName,
                    (double) 0, null, (double) 0, (double) 10, java.sql.DatabaseMetaData.columnNullableUnknown, null,
                    null, null, null, null, ordinal,
                    "YES", null, null, null, null,
                    "NO", "NO"
            });
            ordinal++;
        }
        return buildResultSet(cols, mst, rows.toArray(new Object[0][]));
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) {
        String[] cols = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"};
        char[] mst = {'t', 't', 't', 't', 'i', 't'};
        List<Object[]> rows = new ArrayList<>();
        int seq = 1;
        for (MadrasReader.ColumnMeta c : reader.metadata().columns) {
            if (c.index >= reader.metadata().pkColumns) break;
            rows.add(new Object[]{null, null, tableName, c.name, seq, null});
            seq++;
        }
        return buildResultSet(cols, mst, rows.toArray(new Object[0][]));
    }

    private static int sqlTypeFor(char mstType) {
        switch (mstType) {
            case 't': return Types.VARCHAR;
            case '*': return Types.VARBINARY;
            case 'i': return Types.INTEGER;
            case 'I': return Types.BIGINT;
            case '.': return Types.DOUBLE;
            case '0': case '1': case '2': case '3': case '4':
            case '5': case '6': case '7': case '8': case '9':
                return Types.DECIMAL;
            case 'j': return Types.DATE;
            case 'k': case 'l': return Types.TIME;
            case 'm': case 'n': case 'o': case 'p': case 'q': return Types.TIMESTAMP;
            default: return Types.VARCHAR;
        }
    }

    private static String typeNameFor(char mstType) {
        switch (sqlTypeFor(mstType)) {
            case Types.VARCHAR: return "TEXT";
            case Types.VARBINARY: return "BLOB";
            case Types.INTEGER: return "INTEGER";
            case Types.BIGINT: return "BIGINT";
            case Types.DOUBLE: return "DOUBLE";
            case Types.DECIMAL: return "DECIMAL";
            case Types.DATE: return "DATE";
            case Types.TIME: return "TIME";
            case Types.TIMESTAMP: return "TIMESTAMP";
            default: return "TEXT";
        }
    }


    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    @Override
    public boolean allProceduresAreCallable() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedHigh() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedLow() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean usesLocalFiles() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsColumnAliasing() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsConvert() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsConvert(int arg0, int arg1) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsTableCorrelationNames() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsOrderByUnrelated() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsGroupBy() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsGroupByUnrelated() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsLikeEscapeClause() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsMultipleResultSets() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsNonNullableColumns() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsOuterJoins() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsFullOuterJoins() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws java.sql.SQLException {
        return false;
    }

    @Override
    public java.lang.String getSchemaTerm() throws java.sql.SQLException {
        return null;
    }

    @Override
    public java.lang.String getProcedureTerm() throws java.sql.SQLException {
        return null;
    }

    @Override
    public java.lang.String getCatalogTerm() throws java.sql.SQLException {
        return null;
    }

    @Override
    public boolean isCatalogAtStart() throws java.sql.SQLException {
        return false;
    }

    @Override
    public java.lang.String getCatalogSeparator() throws java.sql.SQLException {
        return null;
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInExists() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInIns() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsUnion() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsUnionAll() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws java.sql.SQLException {
        return false;
    }

    @Override
    public int getMaxBinaryLiteralLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInGroupBy() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxConnections() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxIndexLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxRowSize() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws java.sql.SQLException {
        return false;
    }

    @Override
    public int getMaxStatementLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxStatements() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxTablesInSelect() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxUserNameLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int arg0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public java.sql.ResultSet getProcedures(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getProcedureColumns(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2, java.lang.String arg3) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getColumnPrivileges(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2, java.lang.String arg3) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getTablePrivileges(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getBestRowIdentifier(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2, int arg3, boolean arg4) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getVersionColumns(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getImportedKeys(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getExportedKeys(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getCrossReference(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2, java.lang.String arg3, java.lang.String arg4, java.lang.String arg5) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getTypeInfo() throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getIndexInfo(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2, boolean arg3, boolean arg4) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public boolean ownUpdatesAreVisible(int arg0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int arg0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int arg0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int arg0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int arg0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int arg0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int arg0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int arg0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int arg0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() throws java.sql.SQLException {
        return false;
    }

    @Override
    public java.sql.ResultSet getUDTs(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2, int[] arg3) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public boolean supportsSavepoints() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsNamedParameters() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() throws java.sql.SQLException {
        return false;
    }

    @Override
    public java.sql.ResultSet getSuperTypes(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getSuperTables(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getAttributes(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2, java.lang.String arg3) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public boolean supportsResultSetHoldability(int arg0) throws java.sql.SQLException {
        return false;
    }

    @Override
    public int getResultSetHoldability() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getDatabaseMajorVersion() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getDatabaseMinorVersion() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getSQLStateType() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public boolean locatorsUpdateCopy() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsStatementPooling() throws java.sql.SQLException {
        return false;
    }

    @Override
    public java.sql.RowIdLifetime getRowIdLifetime() throws java.sql.SQLException {
        return java.sql.RowIdLifetime.ROWID_UNSUPPORTED;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws java.sql.SQLException {
        return false;
    }

    @Override
    public java.sql.ResultSet getClientInfoProperties() throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getFunctions(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getFunctionColumns(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2, java.lang.String arg3) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public java.sql.ResultSet getPseudoColumns(java.lang.String arg0, java.lang.String arg1, java.lang.String arg2, java.lang.String arg3) throws java.sql.SQLException {
        return emptyResultSet();
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws java.sql.SQLException {
        return false;
    }

    @Override
    public long getMaxLogicalLobSize() throws java.sql.SQLException {
        return 0L;
    }

    @Override
    public boolean supportsRefCursors() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSharding() throws java.sql.SQLException {
        return false;
    }

}
