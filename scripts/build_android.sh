#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
if command -v cygpath >/dev/null 2>&1; then
    # Native Windows CMake cannot resolve /d/... or /e/... source paths.
    REPO_ROOT="$(cygpath -am "$REPO_ROOT")"
fi

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
ABIS="arm64-v8a"
ABIS_OVERRIDE=""
API="24"
BUILD_TYPE="Release"
BACKEND="cpu"
ARM_ARCH=""
KLEIDIAI="OFF"
MODEL_SET="full"
MODELS=""
DEPLOYMENT_BUILD="ON"
BUILD_ROOT="$REPO_ROOT/build/android"
OUTPUT=""
JOBS=""
CLEAN="OFF"
WITH_CLI="OFF"
STRIP="ON"

usage() {
    cat <<'EOF'
Usage: scripts/build_android.sh [options]

Cross-compile audio.cpp for Android ARM and package the C ABI, headers, C++
runtime, and a no-model device smoke test.

Options:
  --ndk <path>             Android NDK path. Defaults to ANDROID_NDK_HOME or
                           ANDROID_NDK_ROOT.
  --abi <list>             Comma/space list: arm64-v8a, armeabi-v7a, or both.
                           May be repeated. Default: arm64-v8a
  --api <level>            Minimum Android API level. Default: 24
  --backend cpu|vulkan     Compute backend. Default: cpu
  --arm-arch <arch>        Optional GGML ARM -march value, for example
                           armv8.2-a+dotprod. Default: portable ABI baseline
  --kleidiai ON|OFF        Compile KleidiAI kernels. Default: OFF
  --model-set full|core|custom
                           Model composite. Default: full
  --models <list>          Models for --model-set custom (comma/semicolon list)
  --build-type <type>      CMake build type. Default: Release
  --build-root <path>      Intermediate build root. Default: build/android
  --output <path>          Package directory. Default: <build-root>/dist
  --deployment-build       Embed model specs (default)
  --no-deployment-build    Load model specs from the filesystem instead
  --with-cli               Also package the audiocpp_cli executable
  --clean                  Remove selected ABI build and package directories
  --no-strip               Keep symbols in packaged binaries
  -j, --jobs <n>           Parallel build jobs
  -h, --help               Show this help

Examples:
  scripts/build_android.sh --ndk "$ANDROID_NDK_HOME"
  scripts/build_android.sh --abi arm64-v8a,armeabi-v7a --backend vulkan
  scripts/build_android.sh --model-set custom --models kokoro_tts,silero_vad
  scripts/build_android.sh --arm-arch 'armv8.2-a+dotprod'
EOF
}

require_value() {
    if [[ $# -lt 2 || -z "$2" ]]; then
        echo "Missing value for $1" >&2
        exit 1
    fi
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --ndk) require_value "$@"; NDK="$2"; shift 2 ;;
        --abi) require_value "$@"; ABIS="${ABIS_OVERRIDE:+${ABIS_OVERRIDE},}$2"; ABIS_OVERRIDE="$ABIS"; shift 2 ;;
        --api) require_value "$@"; API="$2"; shift 2 ;;
        --backend) require_value "$@"; BACKEND="$2"; shift 2 ;;
        --arm-arch) require_value "$@"; ARM_ARCH="$2"; shift 2 ;;
        --kleidiai) require_value "$@"; KLEIDIAI="$2"; shift 2 ;;
        --model-set) require_value "$@"; MODEL_SET="$2"; shift 2 ;;
        --models) require_value "$@"; MODELS="$2"; shift 2 ;;
        --build-type) require_value "$@"; BUILD_TYPE="$2"; shift 2 ;;
        --build-root) require_value "$@"; BUILD_ROOT="$2"; shift 2 ;;
        --output) require_value "$@"; OUTPUT="$2"; shift 2 ;;
        --deployment-build) DEPLOYMENT_BUILD="ON"; shift ;;
        --no-deployment-build) DEPLOYMENT_BUILD="OFF"; shift ;;
        --with-cli) WITH_CLI="ON"; shift ;;
        --clean) CLEAN="ON"; shift ;;
        --no-strip) STRIP="OFF"; shift ;;
        -j|--jobs) require_value "$@"; JOBS="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 1 ;;
    esac
done

if [[ -z "$NDK" ]]; then
    echo "Android NDK not found. Pass --ndk or set ANDROID_NDK_HOME." >&2
    exit 1
fi
if [[ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
    echo "Invalid Android NDK path: $NDK" >&2
    exit 1
fi
if ! [[ "$API" =~ ^[0-9]+$ ]] || (( API < 24 )); then
    echo "Android API must be an integer >= 24." >&2
    exit 1
fi
case "$BACKEND" in
    cpu) VULKAN="OFF" ;;
    vulkan) VULKAN="ON" ;;
    *) echo "--backend must be cpu or vulkan." >&2; exit 1 ;;
esac
if [[ "$VULKAN" == "ON" ]] && (( API < 28 )); then
    echo "The vendored ggml Vulkan backend requires --api 28 or newer." >&2
    exit 1
fi
case "$KLEIDIAI" in
    ON|OFF) ;;
    *) echo "--kleidiai must be ON or OFF." >&2; exit 1 ;;
esac
case "$MODEL_SET" in
    full|core|custom) ;;
    *) echo "--model-set must be full, core, or custom." >&2; exit 1 ;;
esac
if [[ "$MODEL_SET" == "custom" && -z "$MODELS" ]]; then
    echo "--model-set custom requires --models." >&2
    exit 1
fi
if ! command -v cmake >/dev/null 2>&1; then
    echo "CMake 3.20 or newer is required on PATH." >&2
    exit 1
fi

case "$(uname -s)" in
    Linux*) HOST_TAG="linux-x86_64" ;;
    Darwin*) HOST_TAG="darwin-x86_64" ;;
    MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64" ;;
    *) echo "Unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac
NDK_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
STRIP_BIN="$NDK_BIN/llvm-strip"
READELF_BIN="$NDK_BIN/llvm-readelf"
if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
    STRIP_BIN="$STRIP_BIN.exe"
    READELF_BIN="$READELF_BIN.exe"
fi
for tool in "$STRIP_BIN" "$READELF_BIN"; do
    if [[ ! -f "$tool" ]]; then
        echo "Expected NDK tool not found: $tool" >&2
        exit 1
    fi
done

VULKAN_GLSLC=""
if [[ "$VULKAN" == "ON" ]]; then
    VULKAN_GLSLC="$NDK/shader-tools/$HOST_TAG/glslc"
    if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
        VULKAN_GLSLC="$VULKAN_GLSLC.exe"
    fi
    if [[ ! -f "$VULKAN_GLSLC" ]]; then
        echo "NDK Vulkan shader compiler not found: $VULKAN_GLSLC" >&2
        exit 1
    fi
    if command -v cygpath >/dev/null 2>&1; then
        VULKAN_GLSLC="$(cygpath -am "$VULKAN_GLSLC")"
    fi
fi

GENERATOR=""
NINJA_PROGRAM=""
if command -v ninja >/dev/null 2>&1; then
    GENERATOR="Ninja"
    NINJA_PROGRAM="$(command -v ninja)"
    if command -v cygpath >/dev/null 2>&1; then
        # Native Windows CMake does not reliably resolve POSIX PATH entries.
        NINJA_PROGRAM="$(cygpath -am "$NINJA_PROGRAM")"
    fi
elif [[ "$HOST_TAG" == "windows-x86_64" ]]; then
    echo "Ninja is required on PATH for Windows Android builds." >&2
    exit 1
fi

if [[ -z "$OUTPUT" ]]; then
    OUTPUT="$BUILD_ROOT/dist"
fi
ABIS="${ABIS//,/ }"
read -r -a ABI_LIST <<< "$ABIS"
if [[ ${#ABI_LIST[@]} -eq 0 ]]; then
    echo "No Android ABIs requested." >&2
    exit 1
fi

mkdir -p "$OUTPUT/include"
cp "$REPO_ROOT/include/audiocpp.h" "$OUTPUT/include/audiocpp.h"
cp "$REPO_ROOT/LICENSE" "$OUTPUT/LICENSE"

build_abi() {
    local abi="$1"
    local triple neon expected_machine
    case "$abi" in
        arm64-v8a)
            triple="aarch64-linux-android"
            neon="ON"
            expected_machine="AArch64"
            ;;
        armeabi-v7a)
            triple="arm-linux-androideabi"
            neon="OFF"  # audio.cpp's optional native NEON library is AArch64-only.
            expected_machine="ARM"
            ;;
        *) echo "Unsupported Android ABI: $abi" >&2; exit 1 ;;
    esac

    local build_dir="$BUILD_ROOT/$abi-$BACKEND"
    local jni_dir="$OUTPUT/jniLibs/$abi"
    local bin_dir="$OUTPUT/bin/$abi"
    if [[ "$CLEAN" == "ON" ]]; then
        rm -rf "$build_dir" "$jni_dir" "$bin_dir"
    fi
    mkdir -p "$jni_dir" "$bin_dir"

    local cmake_cmd=(
        cmake -S "$REPO_ROOT" -B "$build_dir"
        -DCMAKE_BUILD_TYPE="$BUILD_TYPE"
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
        -DANDROID_ABI="$abi"
        -DANDROID_PLATFORM="android-$API"
        -DANDROID_STL=c++_shared
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
        -DENGINE_ENABLE_CUDA=OFF
        -DENGINE_ENABLE_HIP=OFF
        -DENGINE_ENABLE_VULKAN="$VULKAN"
        -DENGINE_ENABLE_WEBGPU=OFF
        -DENGINE_ENABLE_SYCL=OFF
        -DENGINE_ENABLE_METAL=OFF
        -DENGINE_ENABLE_RKNN=OFF
        -DENGINE_ENABLE_LLAMAFILE=OFF
        -DENGINE_ENABLE_NATIVE_CPU=OFF
        -DENGINE_ENABLE_OPENMP=OFF
        -DGGML_OPENMP=OFF
        -DGGML_CPU_REPACK=ON
        -DGGML_CPU_KLEIDIAI="$KLEIDIAI"
        -DENGINE_ENABLE_NEON="$neon"
        -DENGINE_BUILD_TESTS=OFF
        -DENGINE_BUILD_EXTENDED_TESTS=OFF
        -DENGINE_BUILD_MODEL_TESTS=OFF
        -DENGINE_BUILD_EXAMPLES=OFF
        -DENGINE_BUILD_WARMBENCH=OFF
        -DAUDIOCPP_BUILD_C_API=ON
        -DAUDIOCPP_BUILD_C_API_STATIC=OFF
        -DAUDIOCPP_BUILD_NATIVE_MODEL_MANAGER=OFF
        -DAUDIOCPP_STATIC_ESPEAK=OFF
        -DAUDIOCPP_DEPLOYMENT_BUILD="$DEPLOYMENT_BUILD"
        -DAUDIOCPP_MODEL_SET="$MODEL_SET"
        -DAUDIOCPP_MODELS="$MODELS"
    )
    if [[ "$abi" == "armeabi-v7a" ]]; then
        # NDK r28 rejects ANDROID_ARM_NEON=OFF; only pass the 32-bit opt-in.
        cmake_cmd+=(-DANDROID_ARM_NEON=ON)
    fi
    if [[ -n "$ARM_ARCH" ]]; then
        cmake_cmd+=(-DGGML_CPU_ARM_ARCH="$ARM_ARCH")
    fi
    if [[ "$VULKAN" == "ON" ]]; then
        local vulkan_sysroot="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot"
        local vulkan_include="${VULKAN_HEADERS_INCLUDE:-$vulkan_sysroot/usr/include}"
        local vulkan_library="$vulkan_sysroot/usr/lib/$triple/$API/libvulkan.so"
        if [[ ! -f "$vulkan_include/vulkan/vulkan.hpp" ]]; then
            echo "Vulkan C++ headers not found: $vulkan_include/vulkan/vulkan.hpp" >&2
            echo "Set VULKAN_HEADERS_INCLUDE to a Vulkan-Headers include directory." >&2
            exit 1
        fi
        if [[ ! -f "$vulkan_include/spirv/unified1/spirv.hpp" &&
              ! -f "$vulkan_include/spirv-headers/spirv.hpp" &&
              ! -f "$vulkan_include/spirv.hpp" ]]; then
            echo "SPIR-V headers not found beside $vulkan_include/vulkan" >&2
            echo "Merge the matching SPIRV-Headers include tree into VULKAN_HEADERS_INCLUDE." >&2
            exit 1
        fi
        if [[ ! -f "$vulkan_library" ]]; then
            echo "Android Vulkan loader not found: $vulkan_library" >&2
            exit 1
        fi
        if command -v cygpath >/dev/null 2>&1; then
            vulkan_include="$(cygpath -am "$vulkan_include")"
            vulkan_library="$(cygpath -am "$vulkan_library")"
        fi
        cmake_cmd+=(
            -DVulkan_GLSLC_EXECUTABLE="$VULKAN_GLSLC"
            -DVulkan_INCLUDE_DIR="$vulkan_include"
            -DVulkan_LIBRARY="$vulkan_library"
        )
        if [[ -n "${GGML_VULKAN_SHADERS_GEN_TOOLCHAIN:-}" ]]; then
            local host_toolchain="$GGML_VULKAN_SHADERS_GEN_TOOLCHAIN"
            if command -v cygpath >/dev/null 2>&1; then
                host_toolchain="$(cygpath -am "$host_toolchain")"
            fi
            cmake_cmd+=(-DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN="$host_toolchain")
        fi
    fi
    if [[ -n "$GENERATOR" ]]; then
        cmake_cmd+=(-G "$GENERATOR" -DCMAKE_MAKE_PROGRAM="$NINJA_PROGRAM")
    fi

    echo "==> Configuring Android $abi ($BACKEND, API $API)"
    "${cmake_cmd[@]}"

    local targets=(audiocpp audiocpp_android_smoke)
    if [[ "$WITH_CLI" == "ON" ]]; then
        targets+=(audiocpp_cli)
    fi
    echo "==> Building Android $abi: ${targets[*]}"
    local build_cmd=(cmake --build "$build_dir" --parallel)
    if [[ -n "$JOBS" ]]; then
        build_cmd+=("$JOBS")
    fi
    build_cmd+=(--target "${targets[@]}")
    "${build_cmd[@]}"

    local library="$build_dir/bin/libaudiocpp.so"
    local smoke="$build_dir/bin/audiocpp_android_smoke"
    for artifact in "$library" "$smoke"; do
        if [[ ! -f "$artifact" ]]; then
            echo "Expected Android artifact missing: $artifact" >&2
            exit 1
        fi
    done

    cp "$library" "$jni_dir/libaudiocpp.so"
    cp "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib/$triple/libc++_shared.so" \
        "$jni_dir/libc++_shared.so"
    cp "$smoke" "$bin_dir/audiocpp_android_smoke"
    if [[ "$WITH_CLI" == "ON" ]]; then
        cp "$build_dir/bin/audiocpp_cli" "$bin_dir/audiocpp_cli"
    fi

    if [[ "$STRIP" == "ON" ]]; then
        "$STRIP_BIN" --strip-unneeded "$jni_dir/libaudiocpp.so"
        "$STRIP_BIN" --strip-unneeded "$jni_dir/libc++_shared.so"
        "$STRIP_BIN" --strip-unneeded "$bin_dir/audiocpp_android_smoke"
        if [[ "$WITH_CLI" == "ON" ]]; then
            "$STRIP_BIN" --strip-unneeded "$bin_dir/audiocpp_cli"
        fi
    fi

    local machine
    machine="$("$READELF_BIN" -h "$jni_dir/libaudiocpp.so" | awk -F: '/Machine:/{gsub(/^[ \t]+/, "", $2); print $2}')"
    if [[ "$machine" != "$expected_machine" ]]; then
        echo "ELF architecture mismatch for $abi: $machine" >&2
        exit 1
    fi
    if ! "$READELF_BIN" -d "$jni_dir/libaudiocpp.so" | awk '/NEEDED.*libc\+\+_shared\.so/{found=1} END{exit !found}'; then
        echo "libaudiocpp.so does not declare libc++_shared.so for $abi" >&2
        exit 1
    fi
    if [[ "$VULKAN" == "ON" ]]; then
        if ! "$READELF_BIN" -d "$jni_dir/libaudiocpp.so" | awk '/NEEDED.*libvulkan\.so/{found=1} END{exit !found}'; then
            echo "Vulkan build does not declare libvulkan.so for $abi" >&2
            exit 1
        fi
    fi
    if ! "$READELF_BIN" --dyn-syms --wide "$jni_dir/libaudiocpp.so" | awk '/audiocpp_abi_version/{found=1} END{exit !found}'; then
        echo "C ABI symbols are missing for $abi" >&2
        exit 1
    fi

    echo "==> Packaged $abi: $jni_dir"
}

for abi in "${ABI_LIST[@]}"; do
    build_abi "$abi"
done

cat <<EOF

Android package ready: $OUTPUT
  Native libraries: $OUTPUT/jniLibs/<abi>
  Debug executables: $OUTPUT/bin/<abi>
  Public header: $OUTPUT/include/audiocpp.h

Copy jniLibs into app/src/main/ and load with System.loadLibrary("audiocpp").
See docs/build/android.md for integration and on-device smoke-test commands.
EOF
