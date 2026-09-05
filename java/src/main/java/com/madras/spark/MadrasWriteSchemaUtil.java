package com.madras.spark;

import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

/**
 * Inverse of MadrasSchemaUtil.sparkTypeFor(): decides an MST_ type char and
 * a default MSE_ encoding char for each output column of a CTAS/INSERT.
 */
final class MadrasWriteSchemaUtil {

    private MadrasWriteSchemaUtil() {}

    static char mstTypeFor(DataType t) {
        if (t == DataTypes.StringType) return 't';       // MST_TEXT
        if (t == DataTypes.BinaryType) return '*';        // MST_BIN
        if (t == DataTypes.ByteType || t == DataTypes.ShortType || t == DataTypes.IntegerType
                || t == DataTypes.BooleanType) return 'i'; // MST_INT
        if (t == DataTypes.LongType) return 'I';           // MST_BIGINT
        if (t == DataTypes.FloatType || t == DataTypes.DoubleType) return '.'; // MST_DECV
        if (t == DataTypes.DateType) return 'j';            // MST_DATE
        if (t == DataTypes.TimestampType) return 'm';       // MST_TIMESTAMP
        // DecimalType and anything else not explicitly handled: surface as
        // text rather than silently mis-typing a numeric column -- CTAS on
        // decimal-typed source columns should cast to double/string first.
        return 't';
    }

    /**
     * Default encoding per column: PK and text columns get trie ('t') for
     * exact-match lookup support; everything else gets plain 'v'. No
     * DuckDB-style sampling-based auto-encoding (word-search 'w' detection,
     * delta/dict selection for numerics) is implemented here -- add if
     * needed, mirroring DetectTextEncoding/DetectNumericEncoding in
     * madras_duckdb_copyto.cpp.
     */
    static char defaultEncodingFor(char mstType, boolean isPk) {
        if (mstType == 't' && isPk) return 't';
        if (mstType == 't') return 't';
        return 'v';
    }

    static char[] mstTypesFor(StructType schema) {
        StructField[] fields = schema.fields();
        char[] types = new char[fields.length];
        for (int i = 0; i < fields.length; i++) {
            types[i] = mstTypeFor(fields[i].dataType());
        }
        return types;
    }
}
