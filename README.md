# audio.cpp for Android ARM

**[English](README.md) | [简体中文](README.zh-CN.md)**

This repository is an Android ARM port of [audio.cpp](https://github.com/0xShug0/audio.cpp), a native C++ audio inference framework built on `ggml`. It adds portable ARM native builds, C API packaging, and an offline TTS demo while preserving the upstream model runtime.

This README intentionally focuses on the Android port. The [upstream README](https://github.com/0xShug0/audio.cpp#readme) remains the reference for the complete model matrix, release news, desktop backends, and ecosystem projects.

## Android ARM and Demo App

The port runs audio.cpp **inside an Android application process** through its existing C ABI. It does not require Python, a local server, or a Java reimplementation of individual models.

### Porting approach

The goal is to adapt platform boundaries rather than fork every model implementation:

| Layer | Approach | Reason |
|---|---|---|
| Inference engine | Reuse upstream `ggml`, model loaders, sessions, and the stable C API. | Keep model behavior aligned with upstream and avoid maintaining Android-specific model forks. |
| CPU compatibility | Use a portable ARMv8-A baseline for `arm64-v8a`; enable NEON where supported and disable host-ISA probing. | A build host must never determine which ARM instructions a phone is allowed to execute. |
| Android toolchain | Disable OpenMP and llamafile in helper builds, link the NDK C++ runtime, and package an unversioned `libaudiocpp.so`. | Avoid undeclared `libomp` dependencies and produce libraries that Gradle/APK packaging can consume directly. |
| Page alignment | Request 16 KB maximum page alignment. | Support current 16 KB-page Android devices while retaining 4 KB compatibility. |
| Binary size | Reuse `AUDIOCPP_MODEL_SET=custom` and `AUDIOCPP_MODELS` to compile only selected model families and their internal dependencies. | A mobile app should not carry every desktop model loader. |
| App boundary | Keep Java/Kotlin limited to UI and lifecycle; use a thin JNI bridge to the C API and retain the native model/session for reuse. | Avoid duplicating inference logic and repeated model initialization. |
| Storage | Extract bundled models to app-private storage and set `TMPDIR` before loading GGUF packages with sidecars. | Android asset paths are not filesystem paths, and `/data/local/tmp` is normally not writable by an app. |
| Unsupported host features | Return explicit errors for workflows that require `ffmpeg`, Python, or external processes. | Android API 24 does not expose the same process-spawning facilities as desktop platforms, and helper programs are not guaranteed to exist. |
| Validation | Check cross-compilation, exported C symbols, ELF alignment and dependencies, then run a no-model smoke test and a real packaged-model inference. | A successful link alone does not prove that an APK can load a model and generate audio. |

Platform-specific changes are kept small and separated from model code so future upstream rebases remain manageable. The branch history is likewise split into native-build, demo, integration-documentation, and README changes for review.

### Supported targets

| Target | Current status |
|---|---|
| `arm64-v8a` | Recommended; native library, JNI demo, and APK build validated |
| `armeabi-v7a` | 32-bit core native library cross-compiles; the demo currently packages ARM64 only |
| Backend | CPU by default; optional Vulkan is not part of the validated demo |
| Android API | Build helper accepts API 24 and newer |
| Demo model | MOSS-TTS-Nano 100M Q8_0, offline CPU inference |

### Build the Android native library

Requirements:

- CMake 3.20 or newer and Ninja;
- Android NDK r26 or newer, with r28 or newer recommended;
- Linux, macOS, or Windows with Git Bash/MSYS2;
- Android SDK platform-tools for `adb` testing.

Build one or both ARM ABIs:

```bash
scripts/build_android.sh --ndk /path/to/android-ndk --abi arm64-v8a
scripts/build_android.sh --ndk /path/to/android-ndk --abi arm64-v8a,armeabi-v7a
```

Build only the model family needed by an application:

```bash
scripts/build_android.sh \
  --ndk /path/to/android-ndk \
  --abi arm64-v8a \
  --model-set custom \
  --models moss_tts_nano
```

The default package is written to `build/android/dist`:

```text
dist/
  include/audiocpp.h
  jniLibs/arm64-v8a/
    libaudiocpp.so
    libc++_shared.so
  bin/arm64-v8a/
    audiocpp_android_smoke
```

See the [Android build and integration guide](docs/build/android.md) for build options, JNI linking, optional Vulkan, ARM feature targets, and `adb` smoke tests.

### Build the offline TTS demo

The [`android-demo/`](android-demo/) app provides text input, background inference, WAV generation, playback, visible error states, and model-driven parameter controls. Model files and generated APKs are intentionally excluded from Git.

1. Download the model described in the [MOSS-TTS-Nano guide](docs/models/moss_tts_nano.md), then place it at:

   ```text
   GGUF/moss-tts-nano-100m-q8_0.gguf
   ```

2. Build the reduced ARM64 library, JNI binding, and model assets:

   ```bash
   bash android-demo/build_native.sh /path/to/android-ndk
   ```

3. With JDK 17 and Android SDK Platform 35 installed, build the APK:

   ```bash
   cd android-demo
   ./gradlew assembleDebug        # Linux/macOS
   gradlew.bat assembleDebug      # Windows
   ```

4. Install the generated APK:

   ```bash
   adb install -r android-demo/app/build/outputs/apk/debug/app-debug.apk
   ```

#### Model-driven parameter controls

The demo leaves the C API family hint empty so the registry can identify the loaded model from its path. It then uses `audiocpp_model_option_count()` and `audiocpp_model_option()` to enumerate the options owned by that model. Neither the JNI bridge nor the Java UI contains a MOSS-specific parameter list.

| Scope | Demo behavior |
|---|---|
| Request | Applies explicitly entered values to the current synthesis request. |
| Session | Recreates the session when the configured session-option signature changes; the loaded model remains reusable. |
| Load | Reloads the model and recreates its session when the load-option signature changes. |

Text and numeric values use text fields; boolean values use default / true / false selectors. Blank fields mean “use the model default,” and only explicit values cross JNI. The model still performs final value validation. The reset button clears overrides without changing the loaded model.

This is a generic parameter interface, not a promise that the demo APK contains every model. The default app bundles MOSS-TTS-Nano and builds only its required native model family. To ship another family, include it in the native build and package its model and dependencies; the discovery and parameter UI need no family-specific branches.

### Runtime integration notes

- Copy the complete model package from Android assets to an app-private directory such as `context.filesDir`; pass its absolute filesystem path to `audiocpp_model_load`.
- Before loading a GGUF with embedded sidecars, set `TMPDIR` to an app-private writable directory. The demo implements this in [`native_tts.cpp`](android-demo/app/src/main/cpp/native_tts.cpp).
- Run blocking inference on a worker thread and reuse native sessions where practical.
- Keep `libc++_shared.so` beside `libaudiocpp.so`, and avoid mixing incompatible NDK runtime copies.
- Optional eSpeak-ng, soxr, ffmpeg, Python, and other model-specific dependencies are not bundled by default. Validate the selected model's complete path separately.

### Validation status and limits

Verified in this port:

- ARM64 full native library and CLI cross-compilation; ARMv7 core library cross-compilation.
- 77 exported `audiocpp_*` symbols on both ARM ABIs, `0x4000` LOAD alignment, and no `libomp` dependency.
- ARM64 demo installation on MuMu Emulator using x86_64 with ARM translation, Android 12 / API 32.
- Chinese TTS generation in approximately 23.1 seconds, producing a non-silent 6-second, 48 kHz, stereo, 16-bit PCM WAV followed by playback.
- Dynamic request parameters with `max_tokens=30`: the model stopped at 30 generated audio frames and produced a non-silent 2.4-second, 48 kHz, stereo, 16-bit PCM WAV (115,200 frames), followed by playback.

Not yet verified:

- Physical ARM Android devices and representative on-device performance.
- Vulkan inference and every model/backend combination.
- Optional third-party model dependencies beyond the demo's MOSS-TTS-Nano path.
- Session-option rebuild and load-option reload paths; the bundled MOSS demo exposes no load-scoped controls.

Emulator success is evidence of the Android/ARM64 loading and inference path, not a guarantee of hardware-independent correctness or performance.

## Embedding in Your Own App

Copy the required ABI directories into `app/src/main/jniLibs`, include `include/audiocpp.h` from a JNI target, and load `audiocpp` before calling the binding. The application owns the registry, model, session, request, and result handles described in the [C API guide](docs/c_api.md).

Bindings do not need to hardcode every model's controls. After loading a model, `audiocpp_model_option_count()` and `audiocpp_model_option()` expose load, session, and request options with names, descriptions, defaults, and range metadata. A UI can generate controls from that metadata while the model remains responsible for final validation. The demo implements this pattern, including default-preserving empty fields and automatic session/model rebuilding when their respective options change.

## Upstream audio.cpp in Brief

Upstream audio.cpp provides 149 audio model families and 250+ variants for TTS, ASR, voice cloning, conversion, separation, music generation, alignment, and related tasks. Its shared runtime includes a CLI, OpenAI-compatible server routes, an embedded WebUI, GGUF model packages, reusable sessions, and multiple hardware backends.

The Android port does not claim that all desktop models are practical on a phone. Model coverage, memory requirements, optional dependencies, and backend support remain model-specific.

### Desktop build quick start

Linux:

```bash
scripts/build_linux.sh --deployment-build --backend cpu --target audiocpp_cli --target audiocpp_server
scripts/build_linux.sh --deployment-build --backend cuda --target audiocpp_cli --target audiocpp_server
```

Windows PowerShell:

```powershell
.\scripts\build_windows.ps1 -DeploymentBuild
.\scripts\build_windows.ps1 -Preset windows-cuda-release -Target audiocpp_cli -DeploymentBuild
```

macOS / Metal:

```bash
scripts/build_metal.sh --deployment-build --target audiocpp_cli
```

Prebuilt desktop packages and complete backend instructions are available from the [upstream Releases page](https://github.com/0xShug0/audio.cpp/releases) and the documentation links below.

### Models and CLI

Install a package with the native model manager:

```bash
audiocpp_model_manager list
audiocpp_model_manager install qwen3_asr_0_6b_q8_0 --models-root models
```

Run a TTS request:

```bash
build/bin/audiocpp_cli \
  --task tts \
  --family moss_tts_nano \
  --model /path/to/model.gguf \
  --backend cpu \
  --text "Hello from audio.cpp." \
  --out build/out/tts.wav
```

Always specify `--family` for model commands. Use `--load-option`, `--session-option`, and `--request-option` for model-specific settings. Inspect the options owned by a loaded model instead of assuming a common parameter set:

```bash
build/bin/audiocpp_cli --model /path/to/model.gguf --family moss_tts_nano --help
build/bin/audiocpp_cli --model /path/to/model.gguf --family moss_tts_nano --inspect
```

See the [CLI and model usage guide](docs/usage.md), [model manager guide](docs/model_manager.md), and [GGUF guide](docs/gguf.md) for details.

### Server and WebUI

```bash
audiocpp_server --ui --ui-management --backend cuda
```

Open `http://127.0.0.1:8080`. The server and WebUI are upstream desktop facilities; the Android demo uses the in-process C API instead. See the [server guide](app/server/README.md) for configuration and API routes.

## Documentation

| Document | Purpose |
|---|---|
| [Android build and integration](docs/build/android.md) | NDK builds, packaging, JNI, model assets, and device smoke tests |
| [C API](docs/c_api.md) | Handle lifetime, ABI compatibility, streaming, logging, and runtime option discovery |
| [CLI and models](docs/usage.md) | Tasks, options, batch processing, and model-document index |
| [Model manager](docs/model_manager.md) | Model catalogs and installation |
| [GGUF](docs/gguf.md) | Packages, precision, and conversion |
| [Desktop builds](docs/build/) | Linux, Windows, HIP, WebGPU, SYCL, and other platform guides |
| [Contributing](CONTRIBUTING.md) | Development, validation, and new-model contributions |

## Notes

- GGUF is a container, not a universal architecture adapter. A GGUF from another project is compatible only when its tensors and metadata match an audio.cpp model implementation.
- Model weights retain their original licenses; see [model licenses](docs/model_licenses.md).
- For upstream news, the full supported-model tables, performance reports, and community projects, use the [original audio.cpp repository](https://github.com/0xShug0/audio.cpp).
