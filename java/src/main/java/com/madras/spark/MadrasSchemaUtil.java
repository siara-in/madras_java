package com.madras.spark;

import com.madras.MadrasReader;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

/**
 * Maps madras column type chars to Spark SQL types, mirroring the type
 * mapping in the DuckDB extension's MadrasBind (MST_* -> LogicalType).
 */
final class MadrasSchemaUtil {

    private MadrasSchemaUtil() {}

    static StructType inferSchema(String path) {
        try (MadrasReader reader = new MadrasReader(path)) {
            MadrasReader.Metadata meta = reader.metadata();
            StructField[] fields = new StructField[meta.columns.size() == 0 ? 0
                    : countDataColumns(reader)];
            int i = 0;
            for (MadrasReader.ColumnMeta c : meta.columns) {
                if (c.type == 'S') break; // stop at secondary-index columns, same as the DuckDB extension
                fields[i++] = new StructField(c.name, sparkTypeFor(c.type), true, null);
            }
            return new StructType(fields);
        }
    }

    private static int countDataColumns(MadrasReader reader) {
        int n = 0;
        for (MadrasReader.ColumnMeta c : reader.metadata().columns) {
            if (c.type == 'S') break;
            n++;
        }
        return n;
    }

    static DataType sparkTypeFor(char mstType) {
        switch (mstType) {
            case 't': return DataTypes.StringType;      // MST_TEXT
            case '*': return DataTypes.BinaryType;       // MST_BIN
            case 'i': return DataTypes.IntegerType;      // MST_INT
            case 'I': return DataTypes.LongType;          // MST_BIGINT
            case '.': return DataTypes.DoubleType;        // MST_DECV
            case '0': case '1': case '2': case '3': case '4':
            case '5': case '6': case '7': case '8': case '9':
                return DataTypes.DoubleType;               // MST_DEC0..9 (fixed-point, surfaced as double)
            case 'j': return DataTypes.DateType;          // MST_DATE
            case 'k': case 'l':                            // MST_TIME / MST_TIME_TZ
                return DataTypes.LongType;                 // no direct Spark TIME type; expose as micros
            case 'm': case 'n': case 'o': case 'p': case 'q':
                return DataTypes.TimestampType;             // MST_TIMESTAMP variants
            default:
                return DataTypes.StringType;
        }
    }
}
