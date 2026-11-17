# Android ARM build

audio.cpp can run in-process in an Android application through its existing C ABI. The Android helper cross-compiles the runtime, packages the NDK C++ runtime, and builds a small executable that verifies the library on a device without downloading a model.

## Requirements

- CMake 3.20 or newer
- Ninja (required on Windows hosts)
- Android NDK r26 or newer; r28 or newer is recommended for current 16 KB-page device support
- Android SDK platform-tools (`adb`) for the on-device smoke test
- Linux, macOS, or Windows with Git Bash/MSYS2 for the build helper

The supported ARM ABIs are:

| ABI | Target | Native audio.cpp NEON library | ggml ARM kernels |
|---|---|---|---|
| `arm64-v8a` | 64-bit ARM, recommended and enabled by default | Enabled | Portable ARMv8-A baseline by default |
| `armeabi-v7a` | Legacy 32-bit ARM | Disabled (the native library is AArch64-only) | ARMv7 NEON enabled |

CPU is the default backend. Vulkan is optional and requires a device/driver supported by ggml; not every model or operator has Vulkan coverage.

## Build

Set `ANDROID_NDK_HOME`, or pass `--ndk`:

```bash
scripts/build_android.sh --ndk /path/to/android-ndk
```

On Windows Git Bash, an SDK installed under `D:\Android\Sdk` looks like:

```bash
scripts/build_android.sh \
  --ndk /d/Android/Sdk/ndk/28.1.13356709 \
  --abi arm64-v8a \
  --backend cpu
```

Build both ARM ABIs:

```bash
scripts/build_android.sh --abi arm64-v8a,armeabi-v7a
```

Enable Vulkan:

```bash
scripts/build_android.sh --abi arm64-v8a --backend vulkan
```

For a smaller application binary, build only the model families you need. Model dependencies are resolved by the same composite-build logic used on desktop:

```bash
scripts/build_android.sh \
  --model-set custom \
  --models kokoro_tts,silero_vad
```

The default is `--model-set full`. Use `--model-set core` for the runtime and built-in VAD loaders only. Model specs are embedded by default so the library does not depend on a source checkout; `--no-deployment-build` restores filesystem-based spec discovery.

The default CPU target is portable ARMv8-A on `arm64-v8a`. For a controlled set of devices that all support an extension, opt in explicitly:

```bash
scripts/build_android.sh --arm-arch 'armv8.2-a+dotprod'
```

Do not use that binary on devices without the selected feature. Host-native CPU detection, OpenMP, CUDA, HIP, Metal, SYCL, WebGPU, RKNN, and llamafile are disabled in Android helper builds. Linking requests 16 KB maximum page alignment and remains compatible with 4 KB-page devices.

Run `scripts/build_android.sh --help` for build-directory, cleaning, API-level, CLI, and parallelism options. API 24 is the minimum accepted by the helper. Add `--with-cli` only when the command-line executable is useful for debugging; it is not needed by an Android app. Native CLI tasks work at API 24, but workflow steps that launch external programs such as ffmpeg report an explicit unsupported-operation error because Android neither exposes `posix_spawnp` at that API level nor guarantees helper executables.

## Package layout

The default output is `build/android/dist`:

```text
dist/
  include/audiocpp.h
  LICENSE
  jniLibs/
    arm64-v8a/
      libaudiocpp.so
      libc++_shared.so
    armeabi-v7a/             # when requested
      libaudiocpp.so
      libc++_shared.so
  bin/
    arm64-v8a/
      audiocpp_android_smoke
      audiocpp_cli           # with --with-cli
```

Copy the required ABI directories under `jniLibs` into `app/src/main/jniLibs`, or point Gradle's `jniLibs.srcDirs` at the package. Keep `libc++_shared.so` beside `libaudiocpp.so`. If the application already packages the same NDK C++ runtime, keep exactly one compatible copy rather than mixing different NDK runtime versions.

Load the library before calling its JNI binding:

```kotlin
class AudioCppNative {
    external fun abiVersion(): Int

    companion object {
        init {
            System.loadLibrary("audiocpp")
        }
    }
}
```

`System.loadLibrary` only loads the library; the `external` method above still needs a JNI implementation. Applications can bind `include/audiocpp.h` from their own JNI C/C++ target, or use a C FFI binding technology. For an app CMake target, set `AUDIOCPP_PACKAGE` to the extracted package root and link the imported library:

```cmake
add_library(audiocpp SHARED IMPORTED)
set_target_properties(audiocpp PROPERTIES
    IMPORTED_LOCATION "${AUDIOCPP_PACKAGE}/jniLibs/${ANDROID_ABI}/libaudiocpp.so"
    INTERFACE_INCLUDE_DIRECTORIES "${AUDIOCPP_PACKAGE}/include")
target_link_libraries(your_jni_target PRIVATE audiocpp)
```

The C ABI exposes opaque handles and never lets C++ exceptions cross the boundary. See [the C API guide](../c_api.md) for lifecycle, request, result, streaming, and logging contracts.

Android assets are compressed and identified by asset paths, not normal filesystem paths. Before passing a model to `audiocpp_model_load`, copy/extract the complete model package to an app-private directory such as `context.filesDir`, and pass the absolute filesystem path. Use `context.cacheDir` (or an absolute `XDG_CACHE_HOME`/`HOME` initialized by the app) for unpack caches. Request only the storage permissions the app actually needs; app-private model storage needs no broad external-storage permission.

Models with embedded GGUF sidecar files unpack them through `std::filesystem::temp_directory_path()`. On Android, the default may be `/data/local/tmp`, which is not writable by an ordinary app. Before calling `audiocpp_model_load`, set `TMPDIR` to an app-private writable directory, such as a dedicated subdirectory of `context.cacheDir`. The demo applies this fix in [`native_tts.cpp`](../../android-demo/app/src/main/cpp/native_tts.cpp).

Inference is blocking and can be memory-intensive. Run it on a native worker thread, keep sessions warm where practical, and choose quantized models sized for the device's available RAM. ARM support does not make every desktop model practical on a phone.

The helper packages the core runtime, not every optional model-specific third-party runtime. Models that dynamically load eSpeak-ng, soxr, or another native helper need a compatible Android build of that dependency and its data files; the default build does not bundle them. Likewise, workflows that require ffmpeg or Python cannot assume those programs exist on Android. Check the selected model's own documentation and test its complete inference path on-device.

## Demo model discovery and parameter scopes

The demo passes a null family hint to `audiocpp_model_load`, allowing the registry to identify a compatible model from its path. After loading, it enumerates `AUDIOCPP_OPTION_SCOPE_REQUEST`, `AUDIOCPP_OPTION_SCOPE_SESSION`, and `AUDIOCPP_OPTION_SCOPE_LOAD` through `audiocpp_model_option_count()` / `audiocpp_model_option()`. Java builds controls from the returned names, descriptions, defaults, value types, and ranges; it does not keep a per-family option table.

Empty controls preserve model defaults. Explicit request values are applied with `audiocpp_request_set_option()`. A changed session-option signature recreates the session, while a changed load-option signature reloads the model and recreates the session. The native bridge compares sorted key/value signatures so changing request-only fields does not force either rebuild.

Capability discovery does not remove build-time model dependencies. The bundled app intentionally compiles and packages MOSS-TTS-Nano only. Another model family must be present in `AUDIOCPP_MODELS`, and its model package and optional native/data dependencies must be supplied and tested. No additional family-specific JNI or parameter-panel code should be necessary.

On MuMu (Android 12 / API 32 with ARM translation), setting the discovered `max_tokens` request option to `30` stopped generation at 30 audio frames and produced a non-silent 2.4-second, 48 kHz stereo PCM WAV. Session rebuild and load reload were not separately exercised; the bundled model exposes no load-scoped controls.

## On-device smoke test

Connect exactly one ARM Android device with USB debugging enabled, then select the matching ABI below. The test checks ABI compatibility, registry creation, and built-in task/model registration; it does not run model inference.

```bash
ABI=arm64-v8a
PKG=build/android/dist
DEVICE_DIR=/data/local/tmp/audiocpp

adb shell mkdir -p "$DEVICE_DIR"
adb push "$PKG/jniLibs/$ABI/libaudiocpp.so" "$DEVICE_DIR/"
adb push "$PKG/jniLibs/$ABI/libc++_shared.so" "$DEVICE_DIR/"
adb push "$PKG/bin/$ABI/audiocpp_android_smoke" "$DEVICE_DIR/"
adb shell chmod 755 "$DEVICE_DIR/audiocpp_android_smoke"
adb shell "LD_LIBRARY_PATH=$DEVICE_DIR $DEVICE_DIR/audiocpp_android_smoke"
```

A successful run prints `audio.cpp Android smoke test passed`, the library/ABI version, and registered family/task counts. Test the actual model package separately on each supported SoC and Android version, especially when using Vulkan or a non-baseline `--arm-arch`.
