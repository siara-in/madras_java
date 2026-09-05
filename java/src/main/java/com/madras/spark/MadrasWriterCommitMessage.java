package com.madras.spark;

import org.apache.spark.sql.connector.write.WriterCommitMessage;

/** Empty marker -- the single partition's file is already fully written by commit() time. */
class MadrasWriterCommitMessage implements WriterCommitMessage {
}
