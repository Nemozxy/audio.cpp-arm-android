#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$APP_DIR/.." && pwd)"
if command -v cygpath >/dev/null 2>&1; then
    APP_DIR="$(cygpath -am "$APP_DIR")"
    ROOT_DIR="$(cygpath -am "$ROOT_DIR")"
fi
NDK="${1:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
ARM_ARCH="${2:-}"
KLEIDIAI="${3:-OFF}"
BACKEND="${4:-cpu}"
API=24
if [[ "$BACKEND" == "vulkan" ]]; then API=28; fi
ABI="arm64-v8a"
BUILD_ROOT="${ANDROID_DEMO_BUILD_ROOT:-$ROOT_DIR/build/android-demo}"
MODELS="${ANDROID_DEMO_MODELS:-moss_tts_nano,moss_transcribe_diarize,qwen3_tts,qwen3_asr,index_tts2,breeze_tts}"
DIST="$BUILD_ROOT/dist"
JNILIBS="$APP_DIR/app/src/main/jniLibs/$ABI"

if [[ -z "$NDK" || ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
    echo "Usage: $0 /path/to/android-ndk [GGML_ARM_ARCH] [KLEIDIAI_ON_OFF] [cpu|vulkan]" >&2
    exit 1
fi

BUILD_ARGS=(
    --ndk "$NDK" --abi "$ABI" --api "$API" --model-set custom
    --models "$MODELS"
    --build-root "$BUILD_ROOT" --output "$DIST" --kleidiai "$KLEIDIAI" --backend "$BACKEND" -j 8
)
if [[ -n "$ARM_ARCH" ]]; then
    BUILD_ARGS+=(--arm-arch "$ARM_ARCH")
fi

TOOL_PATH_ROOT="$ROOT_DIR"
if command -v cygpath >/dev/null 2>&1; then
    TOOL_PATH_ROOT="$(cygpath -u "$ROOT_DIR")"
fi
PATH="$TOOL_PATH_ROOT/build/tools/python/cmake/data/bin:$TOOL_PATH_ROOT/build/tools/ninja:$PATH" \
    "$ROOT_DIR/scripts/build_android.sh" "${BUILD_ARGS[@]}"

mkdir -p "$JNILIBS"
cp "$DIST/jniLibs/$ABI/libaudiocpp.so" "$JNILIBS/"
cp "$DIST/jniLibs/$ABI/libc++_shared.so" "$JNILIBS/"

HOST_TAG="linux-x86_64"
EXE=""
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64"; EXE=".exe" ;;
esac
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
"$LLVM_BIN/clang++$EXE" --target="aarch64-none-linux-android$API" \
    --sysroot="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot" \
    -std=c++17 -O2 -fPIC -shared -Wl,--no-undefined \
    -Wl,-z,max-page-size=16384 -Wl,-soname,libaudiocpp_jni.so \
    -I"$ROOT_DIR/include" -I"$ROOT_DIR/external/ggml/include" \
    "$APP_DIR/app/src/main/cpp/native_tts.cpp" \
    "$ROOT_DIR/src/framework/audio/wav_reader.cpp" \
    -L"$JNILIBS" -laudiocpp -llog -landroid \
    -o "$JNILIBS/libaudiocpp_jni.so"
"$LLVM_BIN/llvm-strip$EXE" --strip-unneeded "$JNILIBS/libaudiocpp_jni.so"

echo "Demo native libraries are ready under $JNILIBS (no model weights are bundled)"
