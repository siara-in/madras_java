// madras_jni.cpp
//
// JNI bindings over madras::dv1::static_trie_map, mirroring the read-side
// API already proven in the Python pybind11 bindings (madras_pybind.cpp):
// open/close, metadata, columnar batch fetch, and index/word lookup.
//
// One native handle per opened file, stored as a jlong pointer cast, same
// pattern as most JNI wrappers (RocksDB, LevelDB JNI, etc.) to avoid needing
// a Java-side handle table.

#include <jni.h>
#include <cstring>
#include <cmath>
#include <memory>
#include <string>
#include <vector>
#include <fstream>
#include <algorithm>
#include <cerrno>

#include "madras/dv1/common.hpp"
#include "madras/dv1/reader/static_trie_map.hpp"
#include "madras/dv1/builder/madras_builder.hpp"
#include "madras_key_convert.hpp" // shared with madras_cli / Python bindings

using namespace madras::dv1;
using namespace madras_cli;

/* ---------------------------------------------------------
   Handle wrapper: owns the static_trie_map and, if not mmap'd,
   the backing buffer.
--------------------------------------------------------- */

struct NativeHandle {
    std::unique_ptr<static_trie_map> stm;
    std::vector<uint8_t> owned_buf; // only used in load_from_mem path
};

static NativeHandle *AsHandle(jlong ptr) {
    return reinterpret_cast<NativeHandle *>(ptr);
}

/* ---------------------------------------------------------
   JNI helpers
--------------------------------------------------------- */

static void ThrowJavaException(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, msg);
}

static std::string JStringToStd(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(s, chars);
    return result;
}

extern "C" {

/* ---------------------------------------------------------
   open / close
--------------------------------------------------------- */

JNIEXPORT jlong JNICALL Java_com_madras_MadrasNative_nativeOpen(
        JNIEnv *env, jclass, jstring jpath, jboolean use_mmap) {
    std::string path = JStringToStd(env, jpath);
    auto *handle = new NativeHandle();
    handle->stm = std::unique_ptr<static_trie_map>(new static_trie_map());

    // NOTE: static_trie_map::load(path) is NOT a real mmap -- it fread()s
    // the whole file into a heap buffer it allocates internally (see
    // static_trie_map.hpp's load()). load()/load_from_mem() both return
    // void; load() throws the raw `errno` int on fopen failure rather than
    // returning a status. The use_mmap flag therefore doesn't currently
    // select a genuinely different I/O strategy -- both paths read the full
    // file into memory, just via two different code paths (the library's
    // own read loop vs. our own ifstream read). Kept as two paths since
    // load_from_mem lets us control/inspect the buffer directly (useful for
    // Android/JNI callers that may want to supply a memory-mapped region
    // themselves in the future), but flagging this so it isn't assumed to
    // give mmap's lazy-paging benefit today.
    try {
        if (use_mmap) {
            handle->stm->load(path.c_str());
        } else {
            std::ifstream f(path, std::ios::binary | std::ios::ate);
            if (!f) {
                delete handle;
                ThrowJavaException(env, ("Cannot open file: " + path).c_str());
                return 0;
            }
            std::streamsize len = f.tellg();
            f.seekg(0);
            handle->owned_buf.resize((size_t) len);
            f.read((char *) handle->owned_buf.data(), len);
            handle->stm->load_from_mem(handle->owned_buf.data(), handle->owned_buf.size());
        }
    } catch (int errnum) {
        delete handle;
        ThrowJavaException(env, (std::string("Failed to open ") + path +
                                  ": errno " + std::to_string(errnum) +
                                  " (" + strerror(errnum) + ")").c_str());
        return 0;
    } catch (const std::exception &e) {
        delete handle;
        ThrowJavaException(env, (std::string("Failed to open ") + path + ": " + e.what()).c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL Java_com_madras_MadrasNative_nativeClose(
        JNIEnv *, jclass, jlong handle_ptr) {
    delete AsHandle(handle_ptr);
}

/* ---------------------------------------------------------
   metadata -> flat string array, parsed on the Java side:
   [rows, pk_columns, col_count, name0, type0, enc0, name1, type1, enc1, ...]
--------------------------------------------------------- */

JNIEXPORT jobjectArray JNICALL Java_com_madras_MadrasNative_nativeMetadata(
        JNIEnv *env, jclass, jlong handle_ptr) {
    auto *h = AsHandle(handle_ptr);
    static_trie_map *stm = h->stm.get();

    uint32_t col_count = stm->get_column_count();
    uintxx_t key_count = stm->get_key_count();
    uintxx_t row_count = key_count > 0 ? key_count : stm->get_node_count();

    std::vector<std::string> out;
    out.push_back(std::to_string(row_count));
    out.push_back(std::to_string(stm->get_pk_col_count()));
    out.push_back(std::to_string(col_count));
    for (uint32_t i = 0; i < col_count; i++) {
        out.push_back(stm->get_column_name(i));
        out.push_back(std::string(1, stm->get_column_type(i)));
        out.push_back(std::string(1, stm->get_column_encoding(i)));
    }

    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize) out.size(), strCls, nullptr);
    for (size_t i = 0; i < out.size(); i++) {
        env->SetObjectArrayElement(arr, (jsize) i, env->NewStringUTF(out[i].c_str()));
    }
    return arr;
}

/* ---------------------------------------------------------
   Columnar fetch: returns one Object[] per requested column, each either a
   double[] (numeric, NaN = null), a String[] (text), or a byte[][] (blob),
   matching MadrasReader.java's expectations.
--------------------------------------------------------- */

JNIEXPORT jobjectArray JNICALL Java_com_madras_MadrasNative_nativeGetColumns(
        JNIEnv *env, jclass, jlong handle_ptr, jlong offset, jlong count,
        jintArray jcol_indices) {
    auto *h = AsHandle(handle_ptr);
    static_trie_map *stm = h->stm.get();

    jsize ncols = env->GetArrayLength(jcol_indices);
    jint *col_idx_buf = env->GetIntArrayElements(jcol_indices, nullptr);

    uintxx_t key_count = stm->get_key_count();
    uintxx_t row_count = key_count > 0 ? key_count : stm->get_node_count();
    if (count < 0) count = (jlong)(row_count > (uintxx_t) offset ? row_count - (uintxx_t) offset : 0);
    uint64_t end = (uint64_t) offset + (uint64_t) count;
    if (end > (uint64_t) row_count) end = row_count;
    uint64_t n = (end > (uint64_t) offset) ? (end - (uint64_t) offset) : 0;

    size_t max_len = stm->get_max_key_len();
    for (jsize c = 0; c < ncols; c++) {
        uintxx_t vlen = stm->get_max_val_len((uint32_t) col_idx_buf[c]);
        if (vlen > max_len) max_len = vlen;
    }
    std::vector<uint8_t> scratch(max_len + 8);

    jclass objCls = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(ncols, objCls, nullptr);

    for (jsize c = 0; c < ncols; c++) {
        uint32_t col = (uint32_t) col_idx_buf[c];
        char dt = stm->get_column_type(col);

        if (dt == MST_TEXT) {
            jclass strCls = env->FindClass("java/lang/String");
            jobjectArray strArr = env->NewObjectArray((jsize) n, strCls, nullptr);
            for (uint64_t i = 0; i < n; i++) {
                col_value_ptr cv; cv.u8_ptr = scratch.data();
                stm->get_col_val((uint64_t) offset + i, col, cv);
                if (cv.length != UINT32_MAX) {
                    jstring s = env->NewStringUTF(std::string((const char *) cv.u8_ptr, cv.length).c_str());
                    env->SetObjectArrayElement(strArr, (jsize) i, s);
                    env->DeleteLocalRef(s);
                }
            }
            env->SetObjectArrayElement(result, c, strArr);
            env->DeleteLocalRef(strArr);

        } else if (dt == MST_BIN) {
            jclass byteArrCls = env->FindClass("[B");
            jobjectArray blobArr = env->NewObjectArray((jsize) n, byteArrCls, nullptr);
            for (uint64_t i = 0; i < n; i++) {
                col_value_ptr cv; cv.u8_ptr = scratch.data();
                stm->get_col_val((uint64_t) offset + i, col, cv);
                if (cv.length != UINT32_MAX) {
                    jbyteArray b = env->NewByteArray((jsize) cv.length);
                    env->SetByteArrayRegion(b, 0, (jsize) cv.length, (const jbyte *) cv.u8_ptr);
                    env->SetObjectArrayElement(blobArr, (jsize) i, b);
                    env->DeleteLocalRef(b);
                }
            }
            env->SetObjectArrayElement(result, c, blobArr);
            env->DeleteLocalRef(blobArr);

        } else {
            // All numeric types -> double[], NaN = null. Java side re-casts
            // to int/long as appropriate based on the metadata type code.
            jdoubleArray dblArr = env->NewDoubleArray((jsize) n);
            std::vector<jdouble> buf(n);
            for (uint64_t i = 0; i < n; i++) {
                col_value_ptr cv; cv.u8_ptr = scratch.data();
                stm->get_col_val((uint64_t) offset + i, col, cv);
                if (cv.length == UINT32_MAX) {
                    buf[i] = std::nan("");
                } else if (dt == MST_INT || dt == MST_DATE) {
                    buf[i] = (double) *cv.i32_ptr;
                } else if (dt == MST_DECV || (dt >= MST_DEC0 && dt <= MST_DEC9)) {
                    buf[i] = *cv.dbl_ptr;
                } else {
                    buf[i] = (double) *cv.i64_ptr;
                }
            }
            env->SetDoubleArrayRegion(dblArr, 0, (jsize) n, buf.data());
            env->SetObjectArrayElement(result, c, dblArr);
            env->DeleteLocalRef(dblArr);
        }
    }

    env->ReleaseIntArrayElements(jcol_indices, col_idx_buf, JNI_ABORT);
    return result;
}

/* ---------------------------------------------------------
   Index/word lookup -> row ids (long[])
--------------------------------------------------------- */

/* ---------------------------------------------------------
   Fetch specific row ids (used after a lookup) -> same Object[] shape as
   nativeGetColumns.
--------------------------------------------------------- */

JNIEXPORT jobjectArray JNICALL Java_com_madras_MadrasNative_nativeGetColumnsByIds(
        JNIEnv *env, jclass, jlong handle_ptr, jlongArray jrow_ids, jintArray jcol_indices) {
    auto *h = AsHandle(handle_ptr);
    static_trie_map *stm = h->stm.get();

    jsize ncols = env->GetArrayLength(jcol_indices);
    jint *col_idx_buf = env->GetIntArrayElements(jcol_indices, nullptr);
    jsize n = env->GetArrayLength(jrow_ids);
    jlong *row_ids = env->GetLongArrayElements(jrow_ids, nullptr);

    size_t max_len = stm->get_max_key_len();
    for (jsize c = 0; c < ncols; c++) {
        uintxx_t vlen = stm->get_max_val_len((uint32_t) col_idx_buf[c]);
        if (vlen > max_len) max_len = vlen;
    }
    std::vector<uint8_t> scratch(max_len + 8);

    jclass objCls = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(ncols, objCls, nullptr);

    for (jsize c = 0; c < ncols; c++) {
        uint32_t col = (uint32_t) col_idx_buf[c];
        char dt = stm->get_column_type(col);

        if (dt == MST_TEXT) {
            jclass strCls = env->FindClass("java/lang/String");
            jobjectArray strArr = env->NewObjectArray(n, strCls, nullptr);
            for (jsize i = 0; i < n; i++) {
                col_value_ptr cv; cv.u8_ptr = scratch.data();
                stm->get_col_val((uint64_t) row_ids[i], col, cv);
                if (cv.length != UINT32_MAX) {
                    jstring s = env->NewStringUTF(std::string((const char *) cv.u8_ptr, cv.length).c_str());
                    env->SetObjectArrayElement(strArr, i, s);
                    env->DeleteLocalRef(s);
                }
            }
            env->SetObjectArrayElement(result, c, strArr);
            env->DeleteLocalRef(strArr);
        } else if (dt == MST_BIN) {
            jclass byteArrCls = env->FindClass("[B");
            jobjectArray blobArr = env->NewObjectArray(n, byteArrCls, nullptr);
            for (jsize i = 0; i < n; i++) {
                col_value_ptr cv; cv.u8_ptr = scratch.data();
                stm->get_col_val((uint64_t) row_ids[i], col, cv);
                if (cv.length != UINT32_MAX) {
                    jbyteArray b = env->NewByteArray((jsize) cv.length);
                    env->SetByteArrayRegion(b, 0, (jsize) cv.length, (const jbyte *) cv.u8_ptr);
                    env->SetObjectArrayElement(blobArr, i, b);
                    env->DeleteLocalRef(b);
                }
            }
            env->SetObjectArrayElement(result, c, blobArr);
            env->DeleteLocalRef(blobArr);
        } else {
            jdoubleArray dblArr = env->NewDoubleArray(n);
            std::vector<jdouble> buf(n);
            for (jsize i = 0; i < n; i++) {
                col_value_ptr cv; cv.u8_ptr = scratch.data();
                stm->get_col_val((uint64_t) row_ids[i], col, cv);
                if (cv.length == UINT32_MAX) {
                    buf[i] = std::nan("");
                } else if (dt == MST_INT || dt == MST_DATE) {
                    buf[i] = (double) *cv.i32_ptr;
                } else if (dt == MST_DECV || (dt >= MST_DEC0 && dt <= MST_DEC9)) {
                    buf[i] = *cv.dbl_ptr;
                } else {
                    buf[i] = (double) *cv.i64_ptr;
                }
            }
            env->SetDoubleArrayRegion(dblArr, 0, n, buf.data());
            env->SetObjectArrayElement(result, c, dblArr);
            env->DeleteLocalRef(dblArr);
        }
    }

    env->ReleaseIntArrayElements(jcol_indices, col_idx_buf, JNI_ABORT);
    env->ReleaseLongArrayElements(jrow_ids, row_ids, JNI_ABORT);
    return result;
}

/* ---------------------------------------------------------
   Index/word lookup -> row ids (long[])
--------------------------------------------------------- */

JNIEXPORT jlongArray JNICALL Java_com_madras_MadrasNative_nativeLookupRowIds(
        JNIEnv *env, jclass, jlong handle_ptr, jint col_idx, jstring jvalue) {
    auto *h = AsHandle(handle_ptr);
    static_trie_map *stm = h->stm.get();
    std::string value = JStringToStd(env, jvalue);

    char col_enc = stm->get_column_encoding((uint32_t) col_idx);
    char data_type = stm->get_column_type((uint32_t) col_idx);
    std::vector<uint64_t> row_ids;

    static_trie_map *trie_map = stm;
    bool is_col_trie = false;
    if ((uint32_t) col_idx >= stm->get_pk_col_count() && col_enc == 'T') {
        trie_map = stm->get_col_trie_map((uint32_t) col_idx);
        if (!trie_map) {
            ThrowJavaException(env, "get_col_trie_map returned null");
            return env->NewLongArray(0);
        }
        is_col_trie = true;
    }

    size_t max_len = stm->get_max_key_len();
    uintxx_t vmax = stm->get_max_val_len((uint32_t) col_idx);
    if (vmax > max_len) max_len = vmax;
    if (is_col_trie) {
        size_t ctm = trie_map->get_max_key_len();
        if (ctm + 1 > max_len) max_len = ctm + 1;
    }

    if (col_enc == 'W') {
        std::vector<uintxx_t> word_positions(value.size() + 1);
        splitter_result sr = get_dflt_word_splitter().split_into_words(
            (const uint8_t *) value.data(), value.size(), UINT32_MAX, word_positions.data());
        struct ctx_t { std::vector<uint64_t> *ids; } ctx { &row_ids };
        auto cb = [](void *c, uintxx_t rid) -> bool {
            ((ctx_t *) c)->ids->push_back(rid);
            return false;
        };
        for (size_t i = 0; i < sr.word_count; i++) {
            const char *word = value.data() + word_positions[i];
            size_t wlen = word_positions[i + 1] - word_positions[i];
            stm->shortlist_word_records((uint32_t) col_idx, word, wlen, cb, &ctx);
        }
        if (sr.word_count > 1) {
            std::sort(row_ids.begin(), row_ids.end());
            std::vector<uint64_t> filtered;
            uint64_t cur = row_ids.empty() ? 0 : row_ids[0];
            size_t cnt = 0;
            for (size_t i = 0; i < row_ids.size(); i++) {
                if (row_ids[i] == cur) cnt++;
                else { if (cnt >= sr.word_count) filtered.push_back(cur); cur = row_ids[i]; cnt = 1; }
            }
            if (cnt >= sr.word_count) filtered.push_back(cur);
            row_ids.swap(filtered);
            std::vector<uint8_t> cv_buf(vmax);
            col_value_ptr cv; cv.u8_ptr = cv_buf.data();
            std::vector<uint64_t> verified;
            for (auto rid : row_ids) {
                stm->get_col_val(rid, (uint32_t) col_idx, cv);
                if (cv.length >= value.size() &&
                    memmem(cv.u8_ptr, cv.length, value.data(), value.size()) != nullptr)
                    verified.push_back(rid);
            }
            row_ids.swap(verified);
        }
    } else {
        std::vector<uint8_t> key(max_len);
        uint32_t key_len = 0;
        ConvertValueToKey(value, data_type, key.data(), key_len);

        iter_ctx it_ctx;
        it_ctx.init(trie_map->get_max_key_len(), trie_map->get_max_level());
        std::vector<uint8_t> out_key_buf(max_len);
        trie_map->find_first(key.data(), key_len, it_ctx, true);
        int out_key_len = trie_map->next(it_ctx, out_key_buf.data());
        while (out_key_len != -2) {
            if ((uint32_t) out_key_len == key_len &&
                memcmp(out_key_buf.data(), key.data(), key_len) == 0) {
                uintxx_t row_id = trie_map->leaf_rank1(it_ctx.node_path[it_ctx.cur_idx]);
                if (is_col_trie) {
                    struct ctx_t { std::vector<uint64_t> *ids; } rcc { &row_ids };
                    auto cb = [](void *c, uintxx_t rid) -> bool {
                        ((ctx_t *) c)->ids->push_back(rid);
                        return false;
                    };
                    static_trie_map::emit_rev_rids(trie_map, row_id, cb, &rcc);
                } else {
                    row_ids.push_back(row_id);
                }
                out_key_len = trie_map->next(it_ctx, out_key_buf.data());
            } else break;
        }
    }

    jlongArray result = env->NewLongArray((jsize) row_ids.size());
    env->SetLongArrayRegion(result, 0, (jsize) row_ids.size(), (const jlong *) row_ids.data());
    return result;
}

/* ===========================================================
   Write path (madras::dv1::builder), for Spark CTAS / DataFrameWriter.
   Mirrors the Python madras_builder_pybind.cpp bindings exactly, just via
   JNI instead of pybind11 -- same builder constructor, append_vector calls,
   insert_record loop for PK indexing, and build_and_write_all().
=========================================================== */

struct WriterHandle {
    std::unique_ptr<madras::dv1::builder> bldr;
    std::vector<char> dt_codes;
    std::vector<char> enc_codes;
    size_t col_count = 0;
    uint16_t pk_col_count = 0;
};

static WriterHandle *AsWriterHandle(jlong ptr) {
    return reinterpret_cast<WriterHandle *>(ptr);
}

JNIEXPORT jlong JNICALL Java_com_madras_MadrasNative_nativeOpenWriter(
        JNIEnv *env, jclass, jstring jpath, jstring jtable_name,
        jobjectArray jcol_names, jstring jdt_codes, jstring jenc_codes, jint pk_col_count) {
    std::string path = JStringToStd(env, jpath);
    std::string table_name = JStringToStd(env, jtable_name);
    std::string dt_codes_str = JStringToStd(env, jdt_codes);
    std::string enc_codes_str = JStringToStd(env, jenc_codes);

    jsize ncols = env->GetArrayLength(jcol_names);
    std::string names_csv = table_name;
    for (jsize i = 0; i < ncols; i++) {
        jstring jname = (jstring) env->GetObjectArrayElement(jcol_names, i);
        names_csv += ",";
        names_csv += JStringToStd(env, jname);
        env->DeleteLocalRef(jname);
    }

    auto *handle = new WriterHandle();
    handle->col_count = (size_t) ncols;
    handle->pk_col_count = (uint16_t) pk_col_count;
    handle->dt_codes.assign(dt_codes_str.begin(), dt_codes_str.end());
    handle->dt_codes.push_back('\0');
    handle->enc_codes.assign(enc_codes_str.begin(), enc_codes_str.end());
    handle->enc_codes.push_back('\0');

    try {
        handle->bldr = std::unique_ptr<madras::dv1::builder>(new madras::dv1::builder(
            path.c_str(), names_csv.c_str(), (int) ncols,
            handle->dt_codes.data(), handle->enc_codes.data(), "0", 0, handle->pk_col_count));
    } catch (const std::exception &e) {
        delete handle;
        ThrowJavaException(env, (std::string("Failed to create builder for ") + path + ": " + e.what()).c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL Java_com_madras_MadrasNative_nativeWriteNumericColumn(
        JNIEnv *env, jclass, jlong handle_ptr, jint col_idx, jdoubleArray jvalues, jbooleanArray jis_null) {
    auto *h = AsWriterHandle(handle_ptr);
    char dt = h->dt_codes[col_idx];

    jsize n = env->GetArrayLength(jvalues);
    jdouble *values = env->GetDoubleArrayElements(jvalues, nullptr);
    jboolean *is_null = env->GetBooleanArrayElements(jis_null, nullptr);

    std::vector<uint64_t> validity((n + 63) / 64, ~0ULL);
    for (jsize i = 0; i < n; i++) {
        if (is_null[i]) validity[i / 64] &= ~(1ULL << (i % 64));
    }

    auto *col_tbl_bldr = h->bldr->get_col_table_bldr();
    if (dt == MST_INT || dt == MST_DATE) {
        std::vector<int32_t> buf(n);
        for (jsize i = 0; i < n; i++) buf[i] = (int32_t) values[i];
        col_tbl_bldr->append_vector((int) col_idx, buf.data(), validity.data(), (size_t) n);
    } else if (dt == MST_BIGINT || (dt >= MST_TIME && dt <= MST_TIMESTAMP_SEC)) {
        std::vector<int64_t> buf(n);
        for (jsize i = 0; i < n; i++) buf[i] = (int64_t) values[i];
        col_tbl_bldr->append_vector((int) col_idx, buf.data(), validity.data(), (size_t) n);
    } else if (dt == MST_DECV || (dt >= MST_DEC0 && dt <= MST_DEC9)) {
        std::vector<double> buf(values, values + n);
        col_tbl_bldr->append_vector((int) col_idx, buf.data(), validity.data(), (size_t) n);
    } else {
        env->ReleaseDoubleArrayElements(jvalues, values, JNI_ABORT);
        env->ReleaseBooleanArrayElements(jis_null, is_null, JNI_ABORT);
        ThrowJavaException(env, "nativeWriteNumericColumn: non-numeric dt code for this column");
        return;
    }

    env->ReleaseDoubleArrayElements(jvalues, values, JNI_ABORT);
    env->ReleaseBooleanArrayElements(jis_null, is_null, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_madras_MadrasNative_nativeWriteTextColumn(
        JNIEnv *env, jclass, jlong handle_ptr, jint col_idx, jobjectArray jvalues) {
    auto *h = AsWriterHandle(handle_ptr);

    jsize n = env->GetArrayLength(jvalues);
    std::vector<std::string> owned(n);       // owns byte data for the duration of this call
    std::vector<db_string_t> views;
    views.reserve(n);
    std::vector<uint64_t> validity((n + 63) / 64, ~0ULL);

    for (jsize i = 0; i < n; i++) {
        jobject item = env->GetObjectArrayElement(jvalues, i);
        if (item == nullptr) {
            validity[i / 64] &= ~(1ULL << (i % 64));
            owned[i] = std::string();
        } else {
            jstring js = (jstring) item;
            owned[i] = JStringToStd(env, js);
        }
        views.emplace_back(owned[i].data(), (uint32_t) owned[i].size());
        if (item != nullptr) env->DeleteLocalRef(item);
    }

    auto *col_tbl_bldr = h->bldr->get_col_table_bldr();
    col_tbl_bldr->append_vector((int) col_idx, views.data(), validity.data(), (size_t) n);
}

JNIEXPORT void JNICALL Java_com_madras_MadrasNative_nativeFinishWriter(
        JNIEnv *env, jclass, jlong handle_ptr) {
    auto *h = AsWriterHandle(handle_ptr);
    try {
        if (h->pk_col_count > 0) {
            auto *col_tbl_bldr = h->bldr->get_col_table_bldr();
            uintxx_t rec_count = col_tbl_bldr->get_record_count();
            std::vector<madras::dv1::col_value> col_vals(h->col_count);
            std::vector<uint8_t> valids(h->col_count);
            for (uintxx_t row = 0; row < rec_count; row++) {
                for (size_t c = 0; c < h->col_count; c++) {
                    madras::dv1::column_storage *col = col_tbl_bldr->get_data(c);
                    col_vals[c] = col->get_col_value(row);
                    valids[c] = (*col->get_null_bv())[row] ? 0 : 1;
                }
                h->bldr->insert_record(col_vals.data(), valids.data(), row, false);
            }
        }
        h->bldr->build_and_write_all();
    } catch (const std::exception &e) {
        ThrowJavaException(env, (std::string("build_and_write_all failed: ") + e.what()).c_str());
        return;
    }
    delete h;
}

JNIEXPORT void JNICALL Java_com_madras_MadrasNative_nativeAbortWriter(
        JNIEnv *, jclass, jlong handle_ptr) {
    // No partial-file cleanup attempted here -- the builder may have already
    // written some data to the output path depending on how far it got.
    // Caller (MadrasDataWriter.abort()) is responsible for deleting the
    // output file if a clean abort is required.
    delete AsWriterHandle(handle_ptr);
}

} // extern "C"
