package com.madras.spark;

import org.apache.spark.sql.connector.write.BatchWrite;
import org.apache.spark.sql.connector.write.DataWriterFactory;
import org.apache.spark.sql.connector.write.PhysicalWriteInfo;
import org.apache.spark.sql.connector.write.WriterCommitMessage;
import org.apache.spark.sql.types.StructType;

import java.io.File;

/**
 * A .mdsi file is one madras::dv1::builder output -- there's no built-in way
 * to merge multiple builder-produced files into one coherent trie after the
 * fact. Rather than silently producing multiple part files (surprising for
 * a "CTAS creates one file" expectation) or attempting a complex post-hoc
 * merge, this requires the write to run as exactly one partition and fails
 * clearly otherwise, telling the caller to .coalesce(1) first -- the same
 * pattern several single-file-oriented Spark sinks use.
 */
class MadrasBatchWrite implements BatchWrite {

    private final String path;
    private final StructType schema;

    MadrasBatchWrite(String path, StructType schema) {
        this.path = path;
        this.schema = schema;
    }

    @Override
    public DataWriterFactory createBatchWriterFactory(PhysicalWriteInfo info) {
        if (info.numPartitions() != 1) {
            throw new IllegalStateException(
                "madras CTAS/write requires exactly 1 partition (got " + info.numPartitions() +
                "), since one .mdsi file is one madras::dv1::builder output that can't be merged " +
                "post-hoc. Call .coalesce(1) on the DataFrame before writing.");
        }
        return new MadrasDataWriterFactory(path, schema);
    }

    @Override
    public void commit(WriterCommitMessage[] messages) {
        // Single partition, single DataWriter -- the file is already fully
        // written and finish()ed by the time this runs. Nothing to merge.
    }

    @Override
    public void abort(WriterCommitMessage[] messages) {
        // Best-effort cleanup of a partially-written file.
        File f = new File(path);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
