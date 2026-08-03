#!/bin/bash
# ============================================================
# Android APK 一键构建脚本
# 用法: apk_build.sh <项目目录> [输出APK路径]
#
# 项目目录结构要求:
#   project/
#     AndroidManifest.xml
#     src/           (Java/Kotlin 源码)
#     res/           (资源文件)
#     libs/          (可选, jar/aar 依赖)
# ============================================================

set -e

PROJECT_DIR="${1:-.}"
OUTPUT_APK="${2:-${PROJECT_DIR}/build/output.apk}"

# 配置
BUILD_TOOLS="/opt/android-sdk/build-tools/34.0.0"
ANDROID_JAR="/opt/android-sdk/platforms/android-34/android.jar"
AAPT2="aapt2"          # 使用系统的 arm64 版本
D8="$BUILD_TOOLS/d8"   # Java 脚本，兼容 arm64
ZIPALIGN="zipalign"
APKSIGNER="apksigner"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-debug}"
KEY_PASS="${KEY_PASS:-android}"

# 颜色
G='\033[0;32m'
R='\033[0;31m'
Y='\033[1;33m'
N='\033[0m'

info()  { echo -e "${G}[INFO]${N} $*"; }
warn()  { echo -e "${Y}[WARN]${N} $*"; }
error() { echo -e "${R}[ERROR]${N} $*"; exit 1; }

info "Building APK from: $PROJECT_DIR"

cd "$PROJECT_DIR"
BUILD_DIR="build"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/obj" "$BUILD_DIR/compiled_res" "$BUILD_DIR/dex"

# ---- 1. 编译资源 ----
info "Step 1/6: Compiling resources (aapt2)..."
find res -name "*.xml" -o -name "*.png" -o -name "*.jpg" 2>/dev/null | while read f; do
    $AAPT2 compile -o "$BUILD_DIR/compiled_res" "$f" 2>&1 || true
done
# 确保至少编译了 values
for f in res/values/*.xml; do
    [ -f "$f" ] && $AAPT2 compile -o "$BUILD_DIR/compiled_res" "$f" 2>&1
done

# ---- 2. 链接 APK 骨架 ----
info "Step 2/6: Linking APK skeleton..."
$AAPT2 link -o "$BUILD_DIR/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    --min-sdk-version 21 \
    --target-sdk-version 34 \
    $(ls "$BUILD_DIR/compiled_res"/*.flat 2>/dev/null) \
    --auto-add-overlay 2>&1

# ---- 3. 编译 Java 源码 ----
info "Step 3/6: Compiling Java sources..."
JAVA_FILES=$(find src -name "*.java" 2>/dev/null)
if [ -n "$JAVA_FILES" ]; then
    LIBS_CP=""
    for jar in libs/*.jar; do
        [ -f "$jar" ] && LIBS_CP="$LIBS_CP:$jar"
    done
    javac -source 1.8 -target 1.8 \
        -bootclasspath "$ANDROID_JAR${LIBS_CP}" \
        -d "$BUILD_DIR/obj" $JAVA_FILES 2>&1
fi

# ---- 4. 编译 Kotlin 源码 ----
KOTLIN_FILES=$(find src -name "*.kt" 2>/dev/null)
if [ -n "$KOTLIN_FILES" ]; then
    info "Step 3b/6: Compiling Kotlin sources..."
    LIBS_CP="$ANDROID_JAR"
    for jar in libs/*.jar; do
        [ -f "$jar" ] && LIBS_CP="$LIBS_CP:$jar"
    done
    kotlinc -cp "$LIBS_CP" -d "$BUILD_DIR/obj" $KOTLIN_FILES 2>&1
fi

# ---- 5. DEX 转换 ----
info "Step 4/6: Converting to DEX (d8)..."
CLASSES=$(find "$BUILD_DIR/obj" -name "*.class" 2>/dev/null)
if [ -n "$CLASSES" ]; then
    $D8 --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex" $CLASSES 2>&1
    # 合并所有 dex 到 APK（需要 cd 到 dex 目录让 aapt 把文件加进去）
    for dex in "$BUILD_DIR"/dex/classes*.dex; do
        if [ -f "$dex" ]; then
            (cd "$BUILD_DIR/dex" && aapt add "../base.apk" "$(basename "$dex")" 2>&1)
        fi
    done
fi

# ---- 6. 对齐和签名 ----
info "Step 5/6: Aligning..."
$ZIPALIGN -f 4 "$BUILD_DIR/base.apk" "$BUILD_DIR/aligned.apk" 2>&1

info "Step 6/6: Signing..."
# 如果 keystore 不存在则自动生成
if [ ! -f "$KEYSTORE" ]; then
    warn "Generating debug keystore..."
    keytool -genkey -v -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KEY_PASS" \
        -dname "CN=Debug, OU=Dev, O=Operit, L=, S=, C=CN" 2>&1
fi

$APKSIGNER sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEY_PASS" \
    "$BUILD_DIR/aligned.apk" 2>&1

cp "$BUILD_DIR/aligned.apk" "$OUTPUT_APK"

info "BUILD SUCCESS: $OUTPUT_APK"
ls -lh "$OUTPUT_APK"
