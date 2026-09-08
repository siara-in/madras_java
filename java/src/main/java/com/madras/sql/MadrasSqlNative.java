package com.madras.sql;

import com.madras.MadrasNative;

/** Native method declarations for the madras_sql engine's JNI layer. */
final class MadrasSqlNative {
    static { MadrasNative.ensureLoaded(); }

    static native long nativeSqlOpen(String path);
    static native void nativeSqlClose(long handle);
    static native String[] nativeSqlExecute(long handle, String sql);

    static native long nativeSqlPrepare(long handle, String sql);
    static native int nativeSqlGetParamCount(long preparedHandle);
    static native String[] nativeSqlExecutePrepared(long preparedHandle, String[] params);
    static native void nativeSqlPreparedClose(long preparedHandle);

    static native void nativeSqlSetIndexLogging(boolean enabled);
    static native boolean nativeSqlGetIndexLogging();
}
