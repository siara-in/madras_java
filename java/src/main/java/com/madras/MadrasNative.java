package com.madras;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Declares the JNI native methods and handles locating/loading the native
 * library for the current platform.
 *
 * Loading strategy (in order):
 *   1. System property "madras.native.path" -- explicit override, loaded via
 *      System.load(absolutePath). Use this if you've placed the library
 *      yourself (e.g. an Android app bundling it under jniLibs/, where
 *      System.loadLibrary("madras_jni") already finds it automatically and
 *      this whole class's resource-extraction path is skipped entirely).
 *   2. System.loadLibrary("madras_jni") -- works as-is on Android (ART
 *      resolves jniLibs/<abi>/libmadras_jni.so automatically) and on any
 *      desktop JVM where the library is already on java.library.path.
 *   3. Extract a bundled resource matching the current OS/arch from the JAR
 *      (natives/<os>-<arch>/libmadras_jni.<ext>) to a temp file and
 *      System.load() it. This is the path desktop users hit when they just
 *      do `implementation("com.madras:madras-jvm:...")` with no manual setup.
 *
 * Resource layout expected inside the JAR (see build.gradle):
 *   natives/linux-x86_64/libmadras_jni.so
 *   natives/linux-aarch64/libmadras_jni.so
 *   natives/darwin-x86_64/libmadras_jni.dylib
 *   natives/darwin-aarch64/libmadras_jni.dylib
 *   natives/windows-x86_64/madras_jni.dll
 *
 * Android is NOT expected to go through this resource-extraction path --
 * Android apps should bundle the NDK-built .so under
 * src/main/jniLibs/<abi>/libmadras_jni.so per standard Android packaging,
 * and step 2 above (System.loadLibrary) picks it up automatically.
 */
final class MadrasNative {

    private static volatile boolean loaded = false;

    static synchronized void ensureLoaded() {
        if (loaded) return;

        String override = System.getProperty("madras.native.path");
        if (override != null) {
            System.load(override);
            loaded = true;
            return;
        }

        try {
            System.loadLibrary("madras_jni");
            loaded = true;
            return;
        } catch (UnsatisfiedLinkError ignored) {
            // Fall through to resource extraction (desktop JAR usage).
        }

        try {
            loadFromResources();
            loaded = true;
        } catch (IOException e) {
            throw new UnsatisfiedLinkError(
                "Could not load madras_jni native library: " + e.getMessage());
        }
    }

    private static void loadFromResources() throws IOException {
        String platformDir = detectPlatformDir();
        String libFileName = platformDir.startsWith("windows")
                ? "madras_jni.dll"
                : platformDir.startsWith("darwin")
                    ? "libmadras_jni.dylib"
                    : "libmadras_jni.so";

        String resourcePath = "/natives/" + platformDir + "/" + libFileName;
        try (InputStream in = MadrasNative.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException(
                    "No bundled native library for platform '" + platformDir +
                    "' at " + resourcePath + ". Build/add it, or set -Dmadras.native.path=<path>.");
            }
            File tmp = File.createTempFile("libmadras_jni", suffixFor(libFileName));
            tmp.deleteOnExit();
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            System.load(tmp.getAbsolutePath());
        }
    }

    private static String suffixFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot == -1 ? "" : fileName.substring(dot);
    }

    private static String detectPlatformDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String archRaw = System.getProperty("os.arch", "").toLowerCase();

        String osName;
        if (os.contains("win")) osName = "windows";
        else if (os.contains("mac") || os.contains("darwin")) osName = "darwin";
        else osName = "linux";

        String arch;
        if (archRaw.contains("aarch64") || archRaw.contains("arm64")) arch = "aarch64";
        else if (archRaw.contains("amd64") || archRaw.contains("x86_64")) arch = "x86_64";
        else arch = archRaw; // fall back to raw value; add mappings as needed

        return osName + "-" + arch;
    }

    // ---------------------------------------------------------------
    // Native method declarations -- implemented in jni/madras_jni.cpp
    // ---------------------------------------------------------------

    static native long nativeOpen(String path, boolean useMmap);

    static native void nativeClose(long handle);

    /**
     * Returns a flat String[]: [rows, pkColumns, colCount,
     * name0, type0, enc0, name1, type1, enc1, ...]
     */
    static native String[] nativeMetadata(long handle);

    /**
     * Returns one Object per requested column index, in the same order as
     * colIndices: double[] for numeric columns (NaN = null), String[] for
     * text columns (null entries = null), byte[][] for blob columns.
     */
    static native Object[] nativeGetColumns(long handle, long offset, long count, int[] colIndices);

    /** Exact-key or word/phrase lookup -> matching row ids. */
    static native long[] nativeLookupRowIds(long handle, int colIdx, String value);

    /**
     * Range lookup. lowerValue is required; upperValue may be null for an
     * open-ended lower-bounded range (col > x / col >= x with no upper
     * limit). An open-ended UPPER-only range (col < x, no lower bound) is
     * NOT supported here -- callers must fall back to a full scan +
     * client-side filter for that case.
     */
    static native long[] nativeRangeLookupRowIds(long handle, int colIdx,
                                                  String lowerValue, boolean lowerInclusive,
                                                  String upperValue, boolean upperInclusive);

    /** Fetches specific row ids -- same Object[] shape as nativeGetColumns. */
    static native Object[] nativeGetColumnsByIds(long handle, long[] rowIds, int[] colIndices);

    // ---------------------------------------------------------------
    // Write path (madras::dv1::builder)
    // ---------------------------------------------------------------

    /**
     * Creates a new .mdsi builder. dtCodes/encCodes are single-char-per-column
     * strings (MST_ and MSE_ codes), same convention as the DuckDB extension's
     * column_dt_ordered/column_enc_ordered.
     */
    static native long nativeOpenWriter(String path, String tableName, String[] colNames,
                                         String dtCodes, String encCodes, int pkColCount);

    static native void nativeWriteNumericColumn(long writerHandle, int colIdx,
                                                 double[] values, boolean[] isNull);

    /** values entries may be null (NULL); dt code for this column must be MST_TEXT/MST_BIN. */
    static native void nativeWriteTextColumn(long writerHandle, int colIdx, String[] values);

    /** Indexes PK rows (if any) and writes the final file. Consumes/frees the handle. */
    static native void nativeFinishWriter(long writerHandle);

    /** Discards the writer without writing a complete file. Consumes/frees the handle. */
    static native void nativeAbortWriter(long writerHandle);
}
