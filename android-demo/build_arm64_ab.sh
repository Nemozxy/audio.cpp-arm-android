#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$APP_DIR/.." && pwd)"
NDK="${1:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
BUILD_ROOT="${ANDROID_DEMO_AB_BUILD_ROOT:-$ROOT_DIR/build/arm64-ab}"
PACKAGE_ROOT="${ANDROID_DEMO_AB_PACKAGE_ROOT:-$BUILD_ROOT/packages}"
VARIANTS="${ANDROID_DEMO_AB_VARIANTS:-baseline dotprod-fp16 dotprod-fp16-kleidiai i8mm i8mm-kleidiai}"

if [[ -z "$NDK" || ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
    echo "Usage: $0 /path/to/android-ndk" >&2
    exit 1
fi

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64"; EXE=".exe" ;;
    *) HOST_TAG="linux-x86_64"; EXE="" ;;
esac
OBJDUMP="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-objdump$EXE"
mkdir -p "$PACKAGE_ROOT"

for VARIANT in $VARIANTS; do
    ARCH=""
    KLEIDIAI="OFF"
    case "$VARIANT" in
        baseline) ;;
        dotprod-fp16|dotprod-fp16-kleidiai) ARCH="armv8.2-a+dotprod+fp16" ;;
        i8mm|i8mm-kleidiai) ARCH="armv8.6-a+i8mm+dotprod+fp16" ;;
        *) echo "Unknown A/B variant: $VARIANT" >&2; exit 1 ;;
    esac
    case "$VARIANT" in
        *-kleidiai) KLEIDIAI="ON" ;;
    esac

    echo "==> Building A/B variant $VARIANT (arch=${ARCH:-portable}, KleidiAI=$KLEIDIAI)"
    ANDROID_DEMO_BUILD_ROOT="$BUILD_ROOT/$VARIANT" \
        ANDROID_DEMO_MODELS=moss_tts_nano,moss_transcribe_diarize \
        bash "$APP_DIR/build_native.sh" "$NDK" "$ARCH" "$KLEIDIAI"

    LIB="$BUILD_ROOT/$VARIANT/dist/jniLibs/arm64-v8a/libaudiocpp.so"
    APK="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
    if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
        # Git Bash/MSYS needs //c to pass cmd.exe's /c switch unchanged.
        (cd "$APP_DIR" && cmd.exe //c "gradlew.bat clean assembleDebug --no-daemon")
    else
        (cd "$APP_DIR" && ./gradlew clean assembleDebug --no-daemon)
    fi

    EXPECTED_LIB_SHA256="$(sha256sum "$LIB" | awk '{print $1}')"
    APK_LIB_SHA256="$(unzip -p "$APK" lib/arm64-v8a/libaudiocpp.so | sha256sum | awk '{print $1}')"
    if [[ "$APK_LIB_SHA256" != "$EXPECTED_LIB_SHA256" ]]; then
        echo "APK native library mismatch for $VARIANT" >&2
        echo "  expected: $EXPECTED_LIB_SHA256 ($LIB)" >&2
        echo "  actual:   $APK_LIB_SHA256 ($APK)" >&2
        exit 1
    fi

    ARTIFACT_DIR="$PACKAGE_ROOT/$VARIANT"
    mkdir -p "$ARTIFACT_DIR"
    cp "$APK" "$ARTIFACT_DIR/audiocpp-$VARIANT.apk"
    cp "$BUILD_ROOT/$VARIANT/arm64-v8a-cpu/CMakeCache.txt" "$ARTIFACT_DIR/CMakeCache.txt"
    cp "$BUILD_ROOT/$VARIANT/arm64-v8a-cpu/build.ninja" "$ARTIFACT_DIR/build.ninja"
    {
        echo "variant=$VARIANT"
        echo "arch=${ARCH:-portable}"
        echo "kleidiai=$KLEIDIAI"
        echo "build_type=Release"
        echo "threads=4 (required at runtime)"
        echo "libaudiocpp_sha256=$EXPECTED_LIB_SHA256"
        echo "apk_libaudiocpp_sha256=$APK_LIB_SHA256"
        echo "git_commit=$(git -C "$ROOT_DIR" rev-parse HEAD)"
        echo "git_dirty=$(git -C "$ROOT_DIR" status --porcelain | wc -l)"
    } > "$ARTIFACT_DIR/variant.txt"
    sha256sum "$ARTIFACT_DIR/audiocpp-$VARIANT.apk" > "$ARTIFACT_DIR/sha256.txt"
    if [[ -n "${ANDROID_DEMO_AB_INPUT:-}" ]]; then
        if [[ ! -f "$ANDROID_DEMO_AB_INPUT" ]]; then
            echo "Benchmark input does not exist: $ANDROID_DEMO_AB_INPUT" >&2
            exit 1
        fi
        sha256sum "$ANDROID_DEMO_AB_INPUT" >> "$ARTIFACT_DIR/sha256.txt"
    fi

    DISASM="$ARTIFACT_DIR/libaudiocpp.disasm.txt"
    "$OBJDUMP" -d "$LIB" > "$DISASM"
    {
        for INSN in sdot udot smmla ummla; do
            COUNT=$(awk -v wanted="$INSN" '{for (i=1; i<=NF; ++i) if ($i == wanted) ++n} END {print n+0}' "$DISASM")
            echo "$INSN=$COUNT"
        done
        COUNT=$(awk '/[[:space:]]fmla[[:space:]]+v[0-9]+\.[48]h/{++n} END {print n+0}' "$DISASM")
        echo "fmla_fp16_vector=$COUNT"
        echo "note=ELF instruction presence is not proof of runtime kernel selection; use ggml.kernel.hit logs."
    } > "$ARTIFACT_DIR/instructions.txt"
    rm -f "$DISASM"
done

echo "A/B packages and build evidence: $PACKAGE_ROOT"
