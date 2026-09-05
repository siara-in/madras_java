# madras-jvm

JNI bindings + a Java reader API + a Spark Data Source V2 connector for
`.mdsi` files, built on the same `static_trie_map` calls already proven in
`madras_cli` and the Python pybind11 bindings.

## Layout

```
madras_jvm/
├── jni/
│   ├── madras_jni.cpp          # JNI native methods (open/close/metadata/columnar fetch/lookup)
│   └── madras_key_convert.hpp  # shared with madras_cli / Python bindings
├── native/
│   └── CMakeLists.txt          # builds libmadras_jni.{so,dylib,dll} for desktop + Android NDK
├── java/src/main/java/com/madras/
│   ├── MadrasNative.java       # native method decls + cross-platform library loading
│   ├── MadrasReader.java       # high-level Java reader API
│   └── spark/                  # Data Source V2 connector
│       ├── MadrasDataSourceProvider.java   # registers format("madras")
│       ├── MadrasSchemaUtil.java           # .mdsi column types -> Spark StructType
│       ├── MadrasTable.java
│       ├── MadrasScanBuilder.java          # supports column pruning
│       ├── MadrasScan.java                 # splits row range into partitions
│       ├── MadrasInputPartition.java
│       ├── MadrasPartitionReaderFactory.java
│       └── MadrasPartitionReader.java      # streams InternalRow per partition
├── java/src/main/resources/META-INF/services/
│   └── org.apache.spark.sql.sources.DataSourceRegister
└── build.gradle
```

## Why Java 8 bytecode

`sourceCompatibility`/`targetCompatibility` are pinned to 8. This is the
lowest common denominator that:
- Runs on any JVM 8+ (covers essentially all Spark clusters in practice --
  Spark itself still supports Java 8/11/17 runtimes).
- Compiles cleanly for Android via D8/R8 desugaring, so the exact same
  `MadrasReader`/`MadrasNative` Java source works in both a desktop JAR and
  an Android app/library module -- no forked code path.

The native side doesn't need language-level changes between platforms
either: Android's ART implements the same JNI contract as a desktop JVM, so
`madras_jni.cpp` is identical for both. Only the *toolchain* differs (see
below).

## Building the native library

**Desktop (Linux/macOS/Windows), one build per target platform:**
```bash
cd native
cmake -B build -DMADRAS_INCLUDE_DIR=/path/to/madras/include
cmake --build build --config Release
# copy the resulting lib into build-out/<os>-<arch>/ matching
# MadrasNative.java's detectPlatformDir() convention, e.g.:
mkdir -p ../build-out/linux-x86_64
cp build/libmadras_jni.so ../build-out/linux-x86_64/
```
Repeat on each OS/arch you need to support (or cross-compile), placing each
output under `native/build-out/<os>-<arch>/libmadras_jni.{so,dylib,dll}`.

**Android (NDK), one build per ABI:**
```bash
cd native
cmake -B build-android-arm64 \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DMADRAS_INCLUDE_DIR=/path/to/madras/include
cmake --build build-android-arm64
```
Repeat for `armeabi-v7a`/`x86_64` as needed. For an Android app, copy each
ABI's `libmadras_jni.so` into `app/src/main/jniLibs/<abi>/` directly --
**skip** the `native/build-out/` resource-packaging step for Android; the
app's own `jniLibs` mechanism is what `System.loadLibrary("madras_jni")`
finds automatically at runtime (see `MadrasNative.ensureLoaded()`'s loading
order).

## Building the Java/Spark JAR

```bash
./gradlew jar
```
Produces a JAR with `com.madras.*` classes, the `META-INF/services` entry
that makes `spark.read.format("madras")` resolve automatically, and any
native libraries you've placed under `native/build-out/` bundled as
resources.

## Building with a `src/madras-trie` submodule layout

If you vendor the trie library as a git submodule at `src/madras-trie`
(containing `include/madras/dv1/...`), here's the exact sequence:

```bash
# 1. Clone this repo and the submodule
git submodule add <madras-trie-repo-url> src/madras-trie
git submodule update --init --recursive

# 2. Native library -- point CMake at the submodule's include dir
cd native
cmake -B build -DMADRAS_INCLUDE_DIR=../src/madras-trie/include
cmake --build build --config Release

mkdir -p build-out/linux-x86_64
cp build/libmadras_jni.so build-out/linux-x86_64/
cd ..
# (repeat per target platform/Android ABI -- see "Building the native
#  library" above for the full desktop + NDK matrix)

# 3. Java/Spark classpath -- point at your Spark install's jars/ directory
export SPARK_HOME=/path/to/spark-3.5.x
SPARK_CP=$(echo $SPARK_HOME/jars/*.jar | tr ' ' ':')

# 4. Compile
mkdir -p build/classes
javac -d build/classes -cp "$SPARK_CP" \
  $(find java/src/main/java -name '*.java')

# 5. Package (with native libs bundled as resources)
mkdir -p build/classes/natives
cp -r native/build-out/* build/classes/natives/
cp -r java/src/main/resources/* build/classes/
cd build/classes && jar cf ../../madras-jvm-0.1.0.jar . && cd ../..
```

If you'd rather use `build.gradle`, point its Spark dependency coordinates
at your target version -- but note Gradle needs Maven Central reachable to
resolve `org.apache.spark:spark-sql_2.12:...` by coordinate. If your build
environment can't reach Maven Central (as was the case in verifying this),
use the manual classpath approach above instead, pointing `javac` directly
at `$SPARK_HOME/jars/*.jar`.

## Using it from Spark

```scala
// Read
val df = spark.read.format("madras").load("/path/to/file.mdsi")
df.select("name", "state").show()

// CTAS / write -- requires exactly one output partition; see
// MadrasBatchWrite's note on why (one .mdsi file is one
// madras::dv1::builder output, no post-hoc multi-partition merge exists)
spark.sql("SELECT * FROM some_table").coalesce(1)
  .write.format("madras").save("/path/to/output.mdsi")
```
or PySpark:
```python
df = spark.read.format("madras").load("/path/to/file.mdsi")
```
Add the JAR via `--jars madras-jvm-0.1.0.jar` or `spark.jars`.

## Verified builds (real toolchain, real headers/jars, actually compiled and run)

- **JNI layer** (`jni/madras_jni.cpp`): compiles with **zero errors** against your real `madras/dv1` headers (read + write paths, 11 native methods total), links into a working `.so`, all symbols verified exported (`nm -D`).
- **Java reader + writer layer** (`MadrasReader`/`MadrasWriter`): compiles clean.
- **JDBC driver** (`java/.../jdbc/*.java`): compiles clean against the full `java.sql.*` interfaces (326 methods across `Connection`/`Statement`/`ResultSet`, every one implemented -- either with real logic or an explicit `SQLFeatureNotSupportedException` for genuinely out-of-scope JDBC features like transactions and updatable result sets).
- **End-to-end runtime tests**: (1) built a real `.mdsi` fixture with your actual `madras::dv1::builder`, ran the Java reader and the JDBC driver against it through `DriverManager` -- full scans, indexed WHERE lookups, LIMIT all correct. (2) Wrote a *new* `.mdsi` file from Java through `MadrasWriter`, read it back, and ran an indexed lookup against the freshly written data -- also correct.
- **Spark connector, read + write**: compiles clean against `spark-catalyst`/`spark-sql`/`spark-sql-api`/`spark-unsafe`/`scala-library` -- **except two lines**, both calling `UTF8String.fromString()`/`getUTF8String()`, blocked on one missing transitive jar: `com.esotericsoftware.kryo.KryoSerializable` (`UTF8String` implements it, so javac needs it resolvable even though this connector never calls Kryo). Confirmed by temporarily isolating those two lines -- everything else, including the entire write path (`WriteBuilder`/`BatchWrite`/`DataWriterFactory`/`DataWriter`), compiles with zero errors.

## Real bugs found and fixed against your actual headers (not assumptions)

- `static_trie_map::load()`/`load_from_mem()` return `void`, not `bool` -- my original code checked a boolean success value that was never there.
- `load()` isn't real mmap -- it `fread()`s the whole file into a heap buffer itself, and throws the raw `errno` int (not an exception object) on failure. Fixed with a `catch (int)`.
- `idx_t` doesn't exist outside DuckDB's own namespace -- I'd carried that type over from the DuckDB extension code by mistake; the real type is `uintxx_t`.
- My own test fixture used the wrong column-encoding char (`'t'` vs `'T'` -- `MSE_TRIE` vs `MSE_TRIE_2WAY`), which silently caused indexed lookups to return nothing until traced down and fixed.
- A javadoc comment containing the literal text `MST_*/MSE_*` terminated its own `/** ... */` block early (the `*/` inside the text closed the comment). Real, if embarrassing, bug -- caught immediately by the compiler.

## What's implemented in the Spark write path vs. still open

**Implemented:** `SupportsWrite`/`WriteBuilder`/`BatchWrite`/`DataWriterFactory`/`DataWriter`, buffering `InternalRow`s into column-major arrays and flushing to the native `madras::dv1::builder` via JNI every 100K rows, single-partition enforcement (clear error + `.coalesce(1)` guidance rather than silently producing multiple unmerged files).

**Known limitations, stated rather than hidden:**
- **Single partition required** -- no post-hoc file-merge exists.
- **No primary-key indexing wired up on the write path yet** -- straightforward to add (thread `isPk` through `MadrasDataWriter`'s `ColumnSpec` construction) but left out since most CTAS targets don't immediately need PK lookup support.
- **`BinaryType` (blob) columns aren't supported on the write path** -- `nativeWriteTextColumn` currently takes `String[]` only; throws a clear `IOException` rather than mangling bytes through a `String`.
- **NaN/null collision**: numeric-column buffering uses `Double.NaN` as its null sentinel. A `DoubleType` column containing a genuine (non-null) `NaN` would be misclassified as NULL. Fix is a parallel boolean-null buffer; flagged in the code, not fixed in this pass.
- **No auto-encoding detection** (word-search vs. plain trie, delta/dict selection for numerics) -- text columns default to plain trie (`'t'`), numeric columns to `'v'`.

## What's left

1. Send the `kryo-shaded` jar and I'll finish verifying the Spark connector compiles fully, then build+run a real CTAS test against it (same style as the JNI/JDBC fixture tests already run).
   ```bash
   for f in $SPARK_HOME/jars/*.jar; do
     unzip -l "$f" 2>/dev/null | grep -q "esotericsoftware/kryo/KryoSerializable.class" && echo "$f"
   done
   ```
2. `DatabaseMetaData` (catalog/driver introspection, ~150 more JDBC methods) was deliberately left unimplemented -- only matters if a BI tool needs to browse schemas rather than just run `SELECT`s.


## JDBC driver (future work, not built here)

You asked whether the JNI layer is reusable for a JDBC driver: yes -- a
`java.sql.Driver`/`Connection`/`Statement`/`ResultSet` implementation would
sit on top of the exact same `MadrasReader`/`MadrasNative` classes used
here, with `ResultSet` column access backed by
`getColumns()`/`getColumnsByIds()` the same way `MadrasPartitionReader`
uses them for Spark. Kept out of this pass as a separate, self-contained
piece of work per your instruction.

## One thing to verify: `db_string_t` construction in the JNI layer

Not applicable here -- this connector is read-only (metadata + fetch +
lookup), so it never touches `madras::dv1::builder`/`ColumnarTableBuilder`'s
write-side `db_string_t` usage. That concern only applies to a future
JVM-side write/COPY TO path, not to anything in this connector.
