# Madras Java

JNI bindings, a Java reader API, and a Spark Data Source V2 connector for Madras Sorcery `.mdsi` files.

## Madras Sorcery

Madras Sorcery is a compact, static datastore where a single `.mdsi` file is simultaneously a compressed column store *and* a sorted, directly-navigable index — no separate index file, no decompress-then-scan step. See the [madras_sorcery](https://github.com/siara-in/madras_sorcery) super-repo for the full project overview.

## Getting started

This repository vendors [madras_sorcery_core](https://github.com/siara-in/madras_sorcery_core) and [madras_sql](https://github.com/siara-in/madras_sql) as git submodules under `src/`, so clone with:

```bash
git clone --recursive https://github.com/siara-in/madras_java.git
# or, if already cloned:
git submodule update --init --recursive
```

### 1. Build the native library

**Desktop (Linux/macOS/Windows), one build per target platform:**

```bash
cd native
cmake -B build
cmake --build build --config Release
mkdir -p ../build-out/linux-x86_64
cp build/libmadras_jni.so ../build-out/linux-x86_64/
```

Repeat on each OS/arch you need, placing each output under `native/build-out/<os>-<arch>/libmadras_jni.{so,dylib,dll}`. To build against a different checkout of the headers instead of the submodule, pass `-DMADRAS_INCLUDE_DIR=/path/to/include` (and `-DMADRAS_SQL_INCLUDE_DIR=/path/to/src` for the SQL engine headers).

**Android (NDK), one build per ABI:**

```bash
cd native
cmake -B build-android-arm64 \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24
cmake --build build-android-arm64
```

For an Android app, copy each ABI's `libmadras_jni.so` into `app/src/main/jniLibs/<abi>/` directly (skip the `native/build-out/` step for Android).

### 2. Build the Java/Spark JAR

```bash
./gradlew jar
```

Produces a JAR with the `com.madras.*` classes, the service registration that makes `spark.read.format("madras")` resolve automatically, and any native libraries under `native/build-out/` bundled as resources.

### 3. Use it

From plain Java:

```java
MadrasReader reader = new MadrasReader("data.mdsi");
```

From Spark:

```scala
val df = spark.read.format("madras").load("data.mdsi")
```

## License

This work is licensed under the MIT License. See [LICENSE](LICENSE).

## Support

Please feel free to communicate suggestions, improvements and corrections by creating issues here or send email to Arundale Ramanathan at arun@siara.in.
