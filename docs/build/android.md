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

Enable Vulkan while retaining the CPU backend and its Repack support:

```bash
scripts/build_android.sh --abi arm64-v8a --api 28 --backend vulkan
```

ggml discovers the NDK's `glslc` and compiles shaders into C++ sources on the build host. The Android device needs only its system Vulkan loader and a compatible driver; it does not need a Vulkan SDK. The NDK supplies the C headers and Android loader, but not the `vulkan/vulkan.hpp` and SPIR-V headers required by ggml. If they are absent, obtain matching Khronos Vulkan-Headers and SPIR-V-Headers, combine their `include` trees in one dependency prefix, and set `VULKAN_HEADERS_INCLUDE` to that prefix's `include` directory. For NDK r28.1, use the `v1.3.275` Vulkan headers and `vulkan-sdk-1.3.275.0` SPIR-V headers:

```bash
VULKAN_HEADERS_INCLUDE=/path/to/Vulkan-Headers/include \
  scripts/build_android.sh --abi arm64-v8a --api 28 --backend vulkan
```

The host also needs a native C++ compiler for `vulkan-shaders-gen`. On Windows, use a configured Visual Studio MSVC environment and, when cross-compiling from a non-MSVC shell, pass a native host toolchain through `GGML_VULKAN_SHADERS_GEN_TOOLCHAIN`:

```bash
GGML_VULKAN_SHADERS_GEN_TOOLCHAIN=/path/to/windows-host-msvc.cmake \
  scripts/build_android.sh --abi arm64-v8a --api 28 --backend vulkan
```

The toolchain must select host `cl.exe`, not the NDK's Android Clang. Vulkan builds require API 28 or newer because the vendored ggml directly links `vkGetPhysicalDeviceFeatures2`; the NDK's ARM64 loader first exports that symbol at API 28. CPU-only builds retain the API 24 minimum. When packaging the demo for Vulkan, pass `-PaudiocppMinSdk=28` to Gradle so its manifest matches the native dependency.

A build with the NDK r28.1 `glslc` currently falls back to the scalar shader path: that compiler rejects cooperative matrix, cooperative matrix2, integer dot product, and bfloat16 feature tests. This is a shader-compiler capability limitation, not evidence that a device lacks the corresponding Vulkan features.

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

## Demo model discovery, custom GGUF import, and parameter scopes

The demo passes a null family hint to `audiocpp_model_load`, allowing the registry to identify a compatible model. After loading, it uses `audiocpp_model_supports()` to gate offline TTS / cloning and ASR tasks, then enumerates `AUDIOCPP_OPTION_SCOPE_REQUEST`, `AUDIOCPP_OPTION_SCOPE_SESSION`, and `AUDIOCPP_OPTION_SCOPE_LOAD` through `audiocpp_model_option_count()` / `audiocpp_model_option()`. Java uses the model metadata for control types, allowed choices, defaults, and numeric bounds. `OptionPresentation` adds Chinese labels, help and placement for selected model options; unrecognized options still render from metadata under Advanced, unless required. The demo exposes list options as read-only because its JNI option path accepts scalars only.

Empty controls preserve model defaults. Explicit request values are applied with `audiocpp_request_set_option()`. A changed session-option signature recreates the session, while a changed load-option signature reloads the model and recreates the session. The native bridge compares sorted key/value signatures so changing request-only fields does not force either rebuild.

The APK does not ship weights. The demo can import a GGUF through `ACTION_OPEN_DOCUMENT`, or list and download GGUF files from `audio-cpp/audio.cpp-gguf` on Hugging Face, Hugging Face Mirror, or ModelScope. The first two catalogs use `main`; ModelScope uses `master`. Online transfers run in a `dataSync` foreground service started from the visible app. The service continues when the app is backgrounded, and its notification shows progress and a cancel action when notifications are allowed. Transfers validate the GGUF header and, for downloads, the expected size and SHA-256 when the source provides it. A partial file is not selectable. Force-stopping the app or a system interruption ends the transfer; retry starts a new download.

Catalogs are cached briefly in the app process. When the Hugging Face Mirror catalog returns HTTP 429, the demo waits before requesting it again, uses its last available list, or shows a list from ModelScope if the mirror has no usable cached list. The dialog marks the fallback; the mirror remains the download source and the selected file is still validated.

Imported and downloaded weights share the app-private model library with files retained from earlier demo versions. They use the same capability and option discovery and are available offline. Only one model and session are retained; changing models releases the previous native objects before loading the replacement.

The online catalog can contain files the APK cannot run. The default `build_native.sh` targets `moss_tts_nano,moss_transcribe_diarize,qwen3_tts,qwen3_asr,index_tts2,breeze_tts`; override the list with `ANDROID_DEMO_MODELS`. The demo supports offline TTS and ASR, not every task in the repository. Use self-contained GGUF packages. The demo does not download sidecars separately.

On MuMu (Android 12 / API 32 with ARM translation), setting the discovered `max_tokens` request option to `30` stopped generation at 30 audio frames and produced a non-silent 2.4-second, 48 kHz stereo PCM WAV. Session rebuild and load reload were not separately exercised; the tested MOSS model exposes no load-scoped controls.

## Demo model workflows

| Model | Main controls and requirements |
|---|---|
| IndexTTS 2 / 2.5 | Reference WAV required. Optional emotion text automatically enables text-based emotion conditioning; blend strength and duration scale are separate. The 2.5 checkpoint also declares language selection and text normalization. |
| BreezeTTS 2 | Optional voice instruction. A reference WAV is optional, but when supplied its transcript is required. |
| Qwen3-TTS Base | Reference WAV required. The reference transcript is required unless speaker-embedding-only mode is enabled. |
| Qwen3-TTS CustomVoice | Select a preset speaker from the loaded checkpoint; optional speaking-style instruction. No reference WAV. |
| Qwen3-TTS VoiceDesign | Optional voice description. No reference WAV or preset speaker. |
| MOSS-TTS-Nano | Default voice when no reference WAV is selected; optional transcript with a reference. The generation limit is in acoustic frames, not speech speed. |
| MOSS-Transcribe-Diarize | Speaker labels and segment timestamps by default. A custom instruction replaces the built-in transcription prompt and may change the output format. |
| Qwen3-ASR | Automatic or checkpoint-supported recognition language, fixed or unchunked offline transcription, and plain-text output. The Demo does not configure VAD or a forced aligner for word timestamps. |

TTS variants are selected by native checkpoint capabilities and declared options, not the GGUF filename. Optional controls preserve model defaults when left blank. Other compiled families use the generic option panel.

## Demo reference-audio voice cloning

The reference-audio controls are gated by `audiocpp_model_supports_speaker_reference()` rather than a family-name check. `ACTION_OPEN_DOCUMENT` imports the selected WAV into app-private storage, so no broad storage permission is needed. The JNI target compiles `src/framework/audio/wav_reader.cpp` alongside its bridge and passes the decoded interleaved float PCM to `audiocpp_request_set_voice_audio()`.

A reference selects `clon/offline` when that task is available; no reference selects `tts/offline`. The task is part of the native session signature, so adding or removing a reference recreates the session. A model's discovered `reference_text` request option is shown next to the selected reference audio and submitted only with that audio. Replacing or removing the recording clears the transcript. For MOSS-TTS-Nano the transcript is optional.

The complete picker-to-clone flow was exercised on MuMu with a 7.616-second, 48 kHz stereo Chinese reference and its transcript. The request created `clon/offline`, consumed 365,568 reference frames, and generated a non-silent 4.48-second stereo WAV followed by playback. Removing the reference then created `tts/offline` and passed a separate `max_tokens=30` synthesis regression.

Custom import was exercised on MuMu with a renamed MOSS-TTS-Nano GGUF. The app detected `moss_tts_nano`, restored its generated controls, synthesized and played a non-silent 8.8-second, 48 kHz stereo WAV (422,400 frames) in 30.7 seconds, and restored the imported model after a cold restart.

## Demo ASR transcription

An `asr/offline` capability replaces the synthesis controls with a WAV picker and a selectable transcript. The same WAV reader supplies interleaved float PCM to `audiocpp_request_set_audio()`, and `audiocpp_result_text()` returns the final text. The request/session/load option path is shared with synthesis, so ASR-specific controls such as `max_tokens` and `instruct` are discovered rather than hardcoded.

Before calling MOSS-Transcribe-Diarize, the JNI bridge mixes mono or multichannel PCM WAV input to mono and converts it to 16 kHz with its built-in linear resampler. This makes non-16-kHz WAV input self-contained in the demo and avoids the model's SOXR-only non-16-kHz path. The lightweight linear converter is a demo convenience; integrations requiring higher-quality resampling can still package and validate SOXR separately.

The final APK was tested on MuMu with a 7.616-second, 16 kHz mono Chinese WAV. It returned `[0.06][S01]它的目标是模拟、延伸和扩展人的智能，让机器能够胜任通常需要人类智慧才能完成的任务。[7.52]`. The UI reported 234.0 seconds including session setup, while the native session took 229.7 seconds and generated 39 tokens. A sampled ASR memory reading was approximately 2.9 GB PSS / 3.0 GB RSS. After adding the built-in converter, the original 7.616-second, 48 kHz stereo WAV was converted from 365,568 stereo frames to 121,856 mono frames and returned the same transcript in 108.6 seconds without SOXR.

## Demo performance reports

Tap **Export performance log** after any TTS or ASR attempt and save the UTF-8 text file through `ACTION_CREATE_DOCUMENT`. This works for successful and failed attempts, requires no broad storage permission, and avoids depending on a host `adb` connection. The report holds the most recent native inference attempt (up to 4 MiB of native diagnostics); starting another inference clears it, and it is not persisted across process restarts.

The report includes:

- app version, device model, SoC metadata where Android exposes it, supported ABIs, Android version, logical processor count, and heap limit;
- model family/file, task, thread count, profiling state, explicit options, final status, and transcript;
- raw `AT_HWCAP` / `AT_HWCAP2` values and hardware FPHP, ASIMDHP, ASIMDDP, I8MM, and SVE booleans from `getauxval()`;
- separate compile-time feature flags, configured ARM architecture, and registered extra buffers. `backend.cpu.features` and ELF instruction presence do not by themselves prove runtime kernel selection;
- model load and session creation time, WAV read/conversion, request setup, total inference, and output details;
- MOSS-Transcribe-Diarize prompt/embedding, Mel extraction, Whisper graph compute, adaptor, prefill, total decode, every decode step, and decode mean/p50/p95/max timings;
- MOSS-TTS token generation, codec graph decode, generated audio duration, and real-time factor (RTF).

The displayed task time and native ASR `total` cover WAV reading, conversion, session creation/reuse, request setup, and inference after the run button is tapped. They exclude model loading performed while selecting the model, as well as audio/model import. The report's `run.timing_scope` records this contract. Export uses a snapshot of the completed run's model, task, thread count, profiling state, status, and transcript rather than the current UI controls; changing models invalidates the previous report.

For a physical-device baseline, use the portable APK, select the same input WAV, keep model parameters at their defaults, and record the thread count. Export the first run and at least one later run without changing settings: the first includes session construction, while later runs reuse the session. Change exactly one variable between comparison groups. The report contains the transcript and explicit option values; review it before sharing.

The demo helper also accepts an optional ggml ARM architecture as its second argument, KleidiAI `ON` / `OFF` as its third argument, and an independent build root through `ANDROID_DEMO_BUILD_ROOT`:

```bash
ANDROID_DEMO_BUILD_ROOT="$PWD/build/android-dotprod" \
  bash android-demo/build_native.sh /path/to/android-ndk 'armv8.2-a+dotprod+fp16' OFF
```

Build all audit variants with independent caches and package the corresponding APK, CMake cache, Ninja commands, hashes, and ELF instruction counts. Each APK is cleaned before packaging, and the helper fails if its packaged `lib/arm64-v8a/libaudiocpp.so` hash does not exactly match that variant's native build:

```bash
ANDROID_DEMO_AB_INPUT=/path/to/fixed-input.wav \
  bash android-demo/build_arm64_ab.sh /path/to/android-ndk
# For a shorter build, set ANDROID_DEMO_AB_VARIANTS="baseline dotprod-fp16".
```

The default variants are `baseline`, `dotprod-fp16`, `dotprod-fp16-kleidiai`, `i8mm`, and `i8mm-kleidiai`. Do not run a specialized APK unless its `getauxval()` report confirms every required hardware feature. I8MM is meaningful only for kernels that actually support it. An x86_64 emulator with ARM translation is useful for loading, correctness, crash, and report-export checks, but its timings must not be presented as physical ARM benchmark results.

Install successive packages with `adb install -r` so the imported benchmark WAV remains in app-private storage. Keep 4 threads, the ASR WAV, TTS text, and model options unchanged. For each variant, export the first (cold-session) run and at least two later runs with **detailed performance analysis** off. Use the same output names plus a variant-specific directory, for example `reports/baseline/asr-warm-01.txt`. Confirm that ASR transcript hashes match before comparing times. Run `dotprod-fp16` against `baseline`, and each KleidiAI build against the non-KleidiAI build with the identical ARM architecture. These are separate APK builds, not runtime dispatch: installing a specialized package on unsupported hardware can fail with an illegal instruction.

## Separate operator-profiling run

Enable **detailed performance analysis** only for a separate diagnostic run. It records per-op wall time (including graph barriers), generic / Repack / KleidiAI kernel hits, worker affinity and observed CPU cores, and one-second CPU-frequency samples. Android may expose frequency files as `unavailable`; this is reported rather than inferred. Per-graph tables are printed only for graphs of at least 1,000 ms by default; set `GGML_CPU_PROFILE_GRAPH_MIN_MS=0` before process startup to print every graph. The final summary still aggregates all graphs.

Keep profiling off for the primary A/B timings, then repeat the same input once with it on. Parse one or more exported reports without manually copying stage values:

```bash
python scripts/parse_android_performance.py reports/*.txt \
  --csv build/arm64-ab/results.csv --json build/arm64-ab/results.json
```

Place each variant's reports in a directory named after that variant so the CSV `variant` column is populated correctly. Interleave variants, repeat runs, preserve run order in file names, and keep the device at a comparable initial thermal state. Do not infer a speedup from compile flags or from one cold run.

## Reference ARM64 A/B results (2026-11-18)

The five Release variants were exercised on a Xiaomi `24117RK2CC` (SM8650, Android 16) with 4 threads and `moss-transcribe-diarize-q4_k.gguf`. The same imported MP3 was decoded to a 1,054,917-frame, 44.1 kHz mono WAV and converted by the demo to 382,736 frames at 16 kHz (23.9208 seconds). Each row below records one normal cold-session run and one separate detailed run; these are reference observations, not repeated-run medians or a promise of improvement on other SoCs. All times are seconds.

| Variant | Normal total | Detailed total | Encoder | Prefill | Decode |
|---|---:|---:|---:|---:|---:|
| `baseline` | 40.624 | 37.751 | 22.548 | 7.956 | 7.152 |
| `dotprod-fp16` | 27.330 | 25.741 | 16.709 | 4.998 | 3.944 |
| `dotprod-fp16-kleidiai` | 36.769 | 31.572 | 20.627 | 6.112 | 4.745 |
| `i8mm` | 25.975 | 23.767 | 15.345 | 4.311 | 4.028 |
| `i8mm-kleidiai` | 28.110 | 23.852 | 15.350 | 4.304 | 4.104 |

The four non-baseline runs generated 121 tokens with identical transcripts and input dimensions, making their inference stages directly comparable. Baseline generated 119 tokens with a small punctuation/text difference, so its end-to-end speedup ratio is approximate rather than token-equivalent. Normal totals include different cold-session construction costs; use `inference` or the warm detailed stages when isolating compute performance. Ordinary and detailed rows are separate executions and must not be combined as if they were one run.

The detailed Q4_K MatMul times were 23.012 seconds for Baseline, 13.652 for DOTPROD+FP16, 16.743 for DOTPROD+FP16+KleidiAI, 11.546 for I8MM, and 11.586 for I8MM+KleidiAI. In the I8MM run, Q4_K and FP32 MatMul accounted for approximately 49.2% and 23.5% of graph wall time, respectively; they remain the primary measured hotspots. Most performance cores sampled approximately 1.824 GHz in the DOTPROD+FP16, I8MM, and I8MM+KleidiAI detailed runs. The DOTPROD+FP16+KleidiAI run sampled lower frequencies (mostly 1.4976/1.5936 GHz), and its broad slowdown must not be attributed to KleidiAI alone.

For these model/tensor paths, all reported MatMul operations used `buffer=generic`; neither KleidiAI variant recorded a KleidiAI or Repack MatMul hit. Registering `CPU_KLEIDIAI,CPU_REPACK` therefore did not provide a measured benefit. A `generic` buffer does not rule out extension instructions inside the ordinary vector-dot implementation: the ARM Q4_K source has an I8MM double-row path using `vmmlaq_s32`, and the specialized ELF contains `smmla`. The current report does not count that instruction-level branch separately, so the precise I8MM hit count remains unverified.

For this reference device and workload, `i8mm` is the best observed candidate, while `i8mm-kleidiai` is effectively tied in the detailed run and offers no demonstrated advantage. Keep the portable ARMv8-A build for general distribution. For a controlled device set whose reports confirm I8MM, DOTPROD, and FP16, an explicit specialized build is:

```bash
scripts/build_android.sh \
  --abi arm64-v8a \
  --backend cpu \
  --arm-arch 'armv8.6-a+i8mm+dotprod+fp16'
```

This recommendation does not enable KleidiAI or imply runtime dispatch. TTS timings are intentionally not ranked here: the runs produced different audio lengths, and MOSS-TTS-Nano output accuracy was not controlled closely enough for a strict comparison.

## Vulkan versus CPU validation

The Vulkan demo build exposes **CPU I8MM** and **Vulkan GPU** selectors in the same APK. Both paths share the same model loader, session options, request options, input conversion, and thread setting. Changing the selector recreates the session. The selected backend is captured in the exported report as `run.backend`; an inference already in progress cannot be changed from the UI.

Build the K80 candidate with 4-thread I8MM, DOTPROD, and FP16 CPU support, KleidiAI off, and Vulkan enabled:

```bash
bash android-demo/build_native.sh /path/to/android-ndk \
  'armv8.6-a+i8mm+dotprod+fp16' OFF vulkan
```

Use this package only on devices whose HWCAP report confirms all three CPU extensions. Install over the existing demo with `adb install -r` to retain the same imported WAV. Select the ASR model, keep 4 threads and all model options unchanged, and compare CPU and Vulkan on the same 382,736-frame, 16 kHz mono input. Record both cold and warm runs, and compare the transcript before interpreting inference times. Then repeat TTS with identical text and sampling parameters, recording token generation, codec graph decode, output frames, and audio duration separately.

A successful session-creation message alone is not GPU-offload evidence. For a detailed run, the exported report must identify the Vulkan registry, the Adreno device description and GPU device type, and model weights in Vulkan buffers. Enable **detailed performance analysis** only for a separate run: it sets `GGML_VK_PERF_LOGGER=1` and `GGML_VK_SYNC_LOGGER=1` before Vulkan initialization. ggml writes its timestamp and synchronization diagnostics to process standard error, not to the app's 4 MiB export buffer. Capture the complete device log around that run:

```bash
adb logcat -c
# Perform exactly one detailed inference in the app.
adb logcat -d > vulkan-detailed-logcat.txt
```

The demo creates one explicitly selected ggml backend per session. It does not use a scheduler that automatically assigns unsupported graph nodes to CPU. An unsupported operation therefore fails the run rather than providing a silent fallback node count. Model-weight buffer names, graph timestamps, transfer/readback stages, and synchronization logs must be evaluated together before claiming GPU execution or attributing a slowdown to transfers.

Physical Adreno performance and precision are not established by compilation or by MuMu ARM translation. See the [on-device ASR results](../reports/android_arm64_asr_performance.md) for the measured CPU and Vulkan comparison. A hybrid Encoder-Vulkan / Decoder-CPU implementation was not tested.

The Debug APK also accepts an ADB-controlled benchmark while the app is in the foreground with the device awake. Import or download and select the model in the demo first; the receiver uses the current saved selection. Use the same GGUF across A/B runs, and import the WAV before testing ASR. The receiver is absent from release builds and requires `android.permission.DUMP` (granted to the ADB shell):

```bash
adb shell am broadcast -n com.audiocpp.ttsdemo/.BenchmarkControlReceiver \
  -a com.audiocpp.ttsdemo.BENCHMARK_CONTROL --es command run --es name cpu-t4 \
  --es task asr --es backend cpu --ei threads 4 --ez profiling false
```

Wait for `cpu-t4.done` under the app-private `files/benchmark-results/` directory, then read the corresponding `cpu-t4.txt` with `adb shell run-as com.audiocpp.ttsdemo cat files/benchmark-results/cpu-t4.txt`. Raw reports and logcat can include transcriptions, file paths and device information. Keep them local and share only explicitly selected, non-identifying metrics.

Package the demo with its matching API floor:

```bash
gradlew.bat assembleDebug -PaudiocppMinSdk=28 --no-daemon
```

The 2026-11-18 Windows build used NDK r28.1, Release `-O3 -DNDEBUG`, `GGML_CPU_ARM_ARCH=armv8.6-a+i8mm+dotprod+fp16`, `GGML_CPU_REPACK=ON`, `GGML_CPU_KLEIDIAI=OFF`, and Vulkan scalar shaders. ELF inspection confirmed AArch64, `NEEDED libvulkan.so`, `NEEDED libc++_shared.so`, and 244 `smmla` instructions in the packaged `libaudiocpp.so`. Instruction presence does not prove a runtime I8MM kernel hit. That historical APK, built before weights were removed, exactly matched the native library hash. It installed and launched on MuMu, identified the retained ASR model, and displayed both CPU I8MM and Vulkan GPU selectors; no model inference was benchmarked there. These are build and compatibility checks, not physical-device inference results.

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
