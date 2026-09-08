// madras_sql_jni.cpp
//
// JNI bindings for the madras_sql engine (src/madras_sql/src/dv1/engine.hpp).
// Separate from madras_jni.cpp's read/write path -- this exposes SQL text
// execution directly, for comparison against the Calcite-based JDBC driver.
//
// Picks the dv1 storage format by including dv1/engine.hpp specifically --
// a dv2-based build of this same JNI layer would only need this one
// #include (and the "using namespace") changed to dv2/engine.hpp /
// dv2sql, nothing else in this file.

#include <jni.h>
#include <string>
#include <vector>

#include "madras/dv1/reader/static_trie_map.hpp"
#include "dv1/engine.hpp"

using namespace madras::dv1;
using namespace dv1sql;

struct SqlHandle {
    std::unique_ptr<static_trie_map> stm;
    std::vector<uint8_t> owned_buf;
    engine eng;
};

// A prepared statement needs to remember which connection's engine it
// belongs to -- sql::query_plan itself has no back-reference, and
// execute_prepared() is a method on engine, not a free function.
struct PreparedHandle {
    SqlHandle *owner;
    sql::query_plan *plan;
};

static SqlHandle *AsHandle(jlong ptr) {
    return reinterpret_cast<SqlHandle *>(ptr);
}

static PreparedHandle *AsPreparedHandle(jlong ptr) {
    return reinterpret_cast<PreparedHandle *>(ptr);
}

static std::string JStringToStd(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(s, chars);
    return result;
}

static void ThrowJavaException(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, msg);
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_madras_sql_MadrasSqlNative_nativeSqlOpen(
        JNIEnv *env, jclass, jstring jpath) {
    std::string path = JStringToStd(env, jpath);
    auto *handle = new SqlHandle();
    handle->stm = std::unique_ptr<static_trie_map>(new static_trie_map());
    try {
        handle->stm->load(path.c_str());
    } catch (int errnum) {
        delete handle;
        ThrowJavaException(env, ("Failed to open " + path + ": errno " + std::to_string(errnum)).c_str());
        return 0;
    } catch (const std::exception &e) {
        delete handle;
        ThrowJavaException(env, (std::string("Failed to open ") + path + ": " + e.what()).c_str());
        return 0;
    }
    handle->eng.init(handle->stm.get());
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL Java_com_madras_sql_MadrasSqlNative_nativeSqlClose(
        JNIEnv *, jclass, jlong handle_ptr) {
    delete AsHandle(handle_ptr);
}

static jobjectArray FlattenResult(JNIEnv *env, const query_result &r) {
    std::vector<std::string> flat;
    flat.push_back(r.ok ? "1" : "0");
    flat.push_back(r.error);
    flat.push_back(std::to_string(r.column_names.size()));
    for (auto &c : r.column_names) flat.push_back(c);
    flat.push_back(std::to_string(r.rows.size()));
    for (auto &row : r.rows) {
        for (auto &cell : row) flat.push_back(cell);
    }

    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize) flat.size(), strCls, nullptr);
    for (size_t i = 0; i < flat.size(); i++) {
        jstring s = env->NewStringUTF(flat[i].c_str());
        env->SetObjectArrayElement(arr, (jsize) i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

// Returns a flat String[]: [ok(0/1), error_or_empty, colCount, colName0..N,
// rowCount, then rowCount*colCount cell values in row-major order].
// Flat/text-based on purpose -- this engine is for comparison/prototyping,
// not (yet) a full typed-value JDBC path the way madras_jni.cpp's
// nativeGetColumns is; matches this engine's own query_result (already
// text-formatted values).
JNIEXPORT jobjectArray JNICALL Java_com_madras_sql_MadrasSqlNative_nativeSqlExecute(
        JNIEnv *env, jclass, jlong handle_ptr, jstring jsql) {
    auto *h = AsHandle(handle_ptr);
    std::string sql = JStringToStd(env, jsql);
    query_result r = h->eng.execute(sql);
    return FlattenResult(env, r);
}

// Parses and plans the SQL ONCE, returning an opaque handle for repeated
// bind-and-execute calls (nativeSqlExecutePrepared) that skip re-parsing
// and re-planning entirely -- this is what actually speeds up repeated
// point queries with different literal values, unlike substituting values
// into SQL text and calling nativeSqlExecute() every time (which still
// re-parses and re-plans from scratch each call). Throws on a SQL parse
// or planning error (e.g. an unsupported WHERE clause shape) rather than
// returning a handle that would fail on every execute.
JNIEXPORT jlong JNICALL Java_com_madras_sql_MadrasSqlNative_nativeSqlPrepare(
        JNIEnv *env, jclass, jlong handle_ptr, jstring jsql) {
    auto *h = AsHandle(handle_ptr);
    std::string sql = JStringToStd(env, jsql);
    std::string error;
    sql::query_plan *plan = h->eng.prepare(sql, error);
    if (plan == nullptr) {
        ThrowJavaException(env, error.c_str());
        return 0;
    }
    auto *ph = new PreparedHandle{h, plan};
    return reinterpret_cast<jlong>(ph);
}

JNIEXPORT jint JNICALL Java_com_madras_sql_MadrasSqlNative_nativeSqlGetParamCount(
        JNIEnv *, jclass, jlong prepared_ptr) {
    auto *ph = AsPreparedHandle(prepared_ptr);
    return ph->owner->eng.param_count(ph->plan);
}

JNIEXPORT jobjectArray JNICALL Java_com_madras_sql_MadrasSqlNative_nativeSqlExecutePrepared(
        JNIEnv *env, jclass, jlong prepared_ptr, jobjectArray jparams) {
    auto *ph = AsPreparedHandle(prepared_ptr);
    jsize n = env->GetArrayLength(jparams);
    std::vector<std::string> params;
    params.reserve(n);
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(jparams, i);
        params.push_back(JStringToStd(env, s));
        env->DeleteLocalRef(s);
    }
    query_result r = ph->owner->eng.execute_prepared(ph->plan, params);
    return FlattenResult(env, r);
}

JNIEXPORT void JNICALL Java_com_madras_sql_MadrasSqlNative_nativeSqlPreparedClose(
        JNIEnv *, jclass, jlong prepared_ptr) {
    auto *ph = AsPreparedHandle(prepared_ptr);
    ph->owner->eng.close_prepared(ph->plan);
    delete ph;
}

// Process-wide toggle for the "Using index: ..." diagnostic logging --
// not per-connection, since it's a plain global (see native.hpp's own
// comment on why that's fine here).
JNIEXPORT void JNICALL Java_com_madras_sql_MadrasSqlNative_nativeSqlSetIndexLogging(
        JNIEnv *, jclass, jboolean enabled) {
    dv1sql::index_logging_enabled() = (enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL Java_com_madras_sql_MadrasSqlNative_nativeSqlGetIndexLogging(
        JNIEnv *, jclass) {
    return dv1sql::index_logging_enabled() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
