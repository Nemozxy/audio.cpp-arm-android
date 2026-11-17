# audio.cpp：Android ARM 移植版

**[English](README.md) | [简体中文](README.zh-CN.md)**

本仓库是 [audio.cpp](https://github.com/0xShug0/audio.cpp) 的 Android ARM 移植分支。原项目是基于 `ggml` 的原生 C++ 音频推理框架；本分支在保留上游模型运行时的基础上，增加便携 ARM 原生构建、C API 打包，以及支持自定义 GGUF 导入的离线 TTS / ASR Demo。

这份 README 重点说明 Android 移植。完整模型清单、版本新闻、桌面后端和生态项目仍以[上游 README](https://github.com/0xShug0/audio.cpp#readme) 为准。

## Android ARM 与 Demo 应用

移植版通过现有 C ABI 让 audio.cpp **直接在 Android 应用进程内运行**，不需要 Python、本地 Server，也不需要用 Java 重新实现各个模型。

### 移植思路

核心原则是只适配平台边界，不为每个模型维护一套 Android 分支：

| 层级 | 方案 | 目的 |
|---|---|---|
| 推理引擎 | 复用上游 `ggml`、模型加载器、会话和稳定的 C API。 | 保持模型行为与上游一致，避免长期维护大量模型分叉。 |
| CPU 兼容性 | `arm64-v8a` 使用便携 ARMv8-A 基线，在支持的层级启用 NEON，并关闭宿主 ISA 探测。 | 防止构建机器的指令集被错误地带入手机二进制。 |
| Android 工具链 | 辅助构建关闭 OpenMP 和 llamafile，打包 NDK C++ 运行时及无版本后缀的 `libaudiocpp.so`。 | 避免隐含的 `libomp` 依赖，让 Gradle 和 APK 可以直接打包原生库。 |
| 页面对齐 | 链接时请求 16 KB 最大页面对齐。 | 支持新的 16 KB 页面 Android 设备，同时保持 4 KB 设备兼容性。 |
| 二进制体积 | 复用 `AUDIOCPP_MODEL_SET=custom` 和 `AUDIOCPP_MODELS`，只编译指定模型家族及内部依赖。 | 避免移动应用携带全部桌面模型加载器。 |
| 应用边界 | Java/Kotlin 只负责界面和生命周期，通过薄 JNI 层调用 C API，并复用原生模型与会话。 | 不重复实现推理逻辑，也避免每次请求重新加载模型。 |
| 存储 | 将内置模型解压到应用私有目录；加载带 sidecar 的 GGUF 前设置 `TMPDIR`。 | Android asset 路径不是文件系统路径，普通应用通常也不能写 `/data/local/tmp`。 |
| 不支持的宿主能力 | 对需要 ffmpeg、Python 或外部进程的工作流返回明确错误。 | Android API 24 的进程接口与桌面不同，也不能假设设备存在辅助程序。 |
| 验证 | 依次检查交叉编译、C 符号、ELF 对齐和依赖，再执行无模型冒烟测试与真实模型推理。 | 链接成功并不等于 APK 能加载模型并生成音频。 |

平台相关修改尽量小，并与模型实现分开，降低以后 rebase 上游源码的成本。分支历史也按原生构建、Demo、集成文档和 README 拆分，便于逐项审查。

### 支持范围

| 目标 | 当前状态 |
|---|---|
| `arm64-v8a` | 推荐；已验证原生库、JNI Demo 和 APK 构建 |
| `armeabi-v7a` | 32 位核心原生库交叉编译成功；Demo 当前只打包 ARM64 |
| 后端 | 默认 CPU；可选 Vulkan 不在当前 Demo 验证范围内 |
| Android API | 构建脚本接受 API 24 及以上 |
| Demo 模型 | MOSS-TTS-Nano 100M Q8_0 与 MOSS-Transcribe-Diarize Q4_K，CPU 离线推理 |

### 构建 Android 原生库

要求：

- CMake 3.20 或更新版本，以及 Ninja；
- Android NDK r26 或更新版本，推荐 r28 或更新版本；
- Linux、macOS，或带 Git Bash/MSYS2 的 Windows；
- 设备测试还需要 Android SDK platform-tools 中的 `adb`。

构建一种或两种 ARM ABI：

```bash
scripts/build_android.sh --ndk /path/to/android-ndk --abi arm64-v8a
scripts/build_android.sh --ndk /path/to/android-ndk --abi arm64-v8a,armeabi-v7a
```

只编译应用需要的模型家族：

```bash
scripts/build_android.sh \
  --ndk /path/to/android-ndk \
  --abi arm64-v8a \
  --model-set custom \
  --models moss_tts_nano
```

默认输出位于 `build/android/dist`：

```text
dist/
  include/audiocpp.h
  jniLibs/arm64-v8a/
    libaudiocpp.so
    libc++_shared.so
  bin/arm64-v8a/
    audiocpp_android_smoke
```

完整构建选项、JNI 链接、可选 Vulkan、ARM 特性目标和 `adb` 冒烟测试见 [Android 构建与集成指南](docs/build/android.md)。

### 构建离线语音 Demo

[`android-demo/`](android-demo/) 应用支持 TTS / ASR 任务选择、内置模型切换、自定义 GGUF 导入、音频选择器、后台推理、WAV 生成与播放、可选择的识别文本、错误状态显示，以及由模型元数据驱动的参数控件。模型文件与生成的 APK 不纳入 Git。

1. 按 [MOSS-TTS-Nano 模型指南](docs/models/moss_tts_nano.md)和 [MOSS-Transcribe-Diarize 模型指南](docs/models/moss_transcribe_diarize.md)下载模型，放到仓库根目录：

   ```text
   GGUF/moss-tts-nano-100m-q8_0.gguf
   GGUF/moss-transcribe-diarize-q4_k.gguf
   ```

2. 构建裁剪后的 ARM64 原生库、JNI 绑定和模型资产。Demo 编译 `moss_tts_nano,moss_transcribe_diarize`，并不压缩地打包两个模型：

   ```bash
   bash android-demo/build_native.sh /path/to/android-ndk
   ```

3. 准备 JDK 17 和 Android SDK Platform 35，构建 APK：

   ```bash
   cd android-demo
   ./gradlew assembleDebug        # Linux/macOS
   gradlew.bat assembleDebug      # Windows
   ```

4. 安装生成的 APK。两个未压缩模型使此调试 APK 约为 956 MB：

   ```bash
   adb install -r android-demo/app/build/outputs/apk/debug/app-debug.apk
   ```

#### 模型驱动的任务与参数控件

Demo 将 C API 的家族提示留空，由注册表自动识别模型，再通过 `audiocpp_model_supports()` 只显示模型实际支持的离线 TTS / 克隆和 ASR 任务。随后通过 `audiocpp_model_option_count()` 和 `audiocpp_model_option()` 枚举该模型实际拥有的参数。JNI 桥接层和 Java 界面均没有 MOSS 专用参数清单。

| 作用域 | Demo 行为 |
|---|---|
| 请求参数 | 将显式填写的值应用到当前推理请求。 |
| 会话参数 | 会话参数签名变化时重建会话；已加载的模型仍可复用。 |
| 加载参数 | 加载参数签名变化时重新加载模型并重建会话。 |

文本和数值使用输入框，布尔值使用“默认 / true / false”选择器。空白字段表示使用模型默认值，只有显式填写的值才会传入 JNI，最终参数校验仍由模型完成。“恢复模型默认参数”只清空覆盖值，不会更换已加载模型。

#### 自定义 GGUF 导入

“导入 GGUF”通过 Android 系统文件选择器选择文件，校验 GGUF 魔数后复制到应用私有目录，不需要额外存储权限。导入模型与内置模型使用相同的家族识别、能力查询和动态参数界面，下次启动时会恢复上次选择。

自定义导入不会在运行时增加原生代码：默认 APK 只接受已编译的 `moss_tts_nano` 和 `moss_transcribe_diarize` 两个家族的 GGUF。支持其他家族时，仍需将其加入 `AUDIOCPP_MODELS` 并提供可选原生或数据依赖，但 Java 和参数面板不需要按家族增加分支。

#### 参考音频语音克隆

当 `audiocpp_model_supports_speaker_reference()` 报告支持时，Demo 会显示可选的参考音频区域。通过 Android 系统文件选择器将 WAV 导入应用私有目录，JNI 桥接层复用 audio.cpp 的 WAV 解码器，并通过 `audiocpp_request_set_voice_audio()` 传入 PCM。

选择参考音频后，如果模型支持 `clon` 任务，Demo 会创建 `clon/offline` 会话；移除参考音频则恢复 `tts/offline`，任务切换会重建会话。有参考音频文字稿时，在自动发现的 `reference_text` 请求参数中填写。MOSS-TTS-Nano 也支持没有文字稿的语音克隆。

#### ASR 语音识别

对于声明支持 `asr/offline` 的模型，Demo 会将合成控件替换为 WAV 选择器和识别文本区域。JNI 使用 audio.cpp 的 WAV 解码器读取 PCM，通过 `audiocpp_request_set_audio()` 传入音频，并通过 `audiocpp_result_text()` 读取最终文本。MOSS-Transcribe-Diarize 的文本可以包含时间戳和说话人标签。

原生层只保留一个模型和会话。切换内置或导入模型时，会先释放上一会话和模型，再加载下一个，避免 TTS 与 ASR 模型同时驻留内存。默认 MOSS ASR 路径可直接处理 16 kHz PCM；其他采样率需要打包 `libsoxr`，当前 Demo 尚未包含该依赖。

### 运行时接入注意事项

- 将完整模型包从 assets 解压到 `context.filesDir` 等应用私有目录，再把绝对文件路径传给 `audiocpp_model_load`。
- 加载包含内嵌 sidecar 的 GGUF 前，将 `TMPDIR` 设置为应用私有的可写目录。Demo 已在 [`native_tts.cpp`](android-demo/app/src/main/cpp/native_tts.cpp) 中实现。
- 在后台线程执行阻塞推理，并尽可能复用原生会话。
- 将 `libc++_shared.so` 与 `libaudiocpp.so` 放在同一 ABI 目录，不要混用不兼容的 NDK 运行时副本。
- 默认不打包 eSpeak-ng、soxr、ffmpeg、Python 等可选模型依赖。选择其他模型时，需要单独验证完整推理路径。

### 验证状态与边界

已验证：

- ARM64 全模型原生库和 CLI 交叉编译成功；ARMv7 核心库交叉编译成功。
- 两种 ARM ABI 均导出 77 个 `audiocpp_*` 符号，LOAD 段按 `0x4000` 对齐，且不依赖 `libomp`。
- ARM64 Demo 已安装到 MuMu 模拟器（x86_64，启用 ARM 翻译，Android 12 / API 32）。
- 中文文本合成耗时约 23.1 秒，生成非静音的 6 秒、48 kHz、双声道、16-bit PCM WAV，并完成播放。
- 动态请求参数 `max_tokens=30`：模型生成 30 个音频帧后停止，输出非静音的 2.4 秒、48 kHz、双声道、16-bit PCM WAV（115,200 帧），并完成播放。
- 通过系统文件选择器导入 7.616 秒的中文参考 WAV 及其文字稿进行语音克隆。原生请求使用 `clon/offline` 和 365,568 帧参考音频，生成并播放了非静音的 4.48 秒、48 kHz 双声道 WAV（215,040 输出帧）。移除参考音频并恢复 `tts/offline` 的回归测试也已通过。
- 最终 APK 使用 MOSS-Transcribe-Diarize Q4_K 识别 7.616 秒、16 kHz 单声道中文 WAV。`asr/offline` 请求返回了与参考句一致的正文，并带 `[0.06][S01]` 和 `[7.52]` 标记；界面总耗时 234.0 秒，原生会话耗时 229.7 秒。MuMu 上 ASR 内存约为 2.9 GB PSS。
- 通过系统文件选择器导入重命名的 MOSS-TTS-Nano 自定义 GGUF，自动识别为 `moss_tts_nano` 并恢复动态参数控件；30.7 秒生成并播放了非静音的 8.8 秒双声道 WAV（422,400 帧），冷启动后也能恢复该导入模型。

尚未验证：

- 物理 ARM Android 设备，以及具有代表性的真机性能。
- Vulkan 推理和全部模型与后端组合。
- 基于 SOXR 的非 16 kHz ASR 重采样及其他可选第三方依赖；默认 APK 未打包 `libsoxr`。
- 会话参数触发的重建与加载参数触发的重新加载；当前内置 MOSS Demo 没有开放加载作用域控件。

模拟器成功可以证明 Android/ARM64 的加载和推理路径已打通，但不能证明所有真机上的正确性与性能。

## 接入自己的应用

将所需 ABI 目录复制到 `app/src/main/jniLibs`，在 JNI 目标中引入 `include/audiocpp.h`，并在调用绑定前加载 `audiocpp`。应用按照 [C API 指南](docs/c_api.md) 管理 registry、model、session、request 和 result 句柄。

绑定层不需要硬编码每个模型的参数。模型加载后，可以通过 `audiocpp_model_option_count()` 和 `audiocpp_model_option()` 获取加载、会话和请求作用域中的选项，包括名称、说明、默认值和范围元数据。界面可以据此动态生成控件，最终校验仍由模型负责。Demo 已实现这一模式，包括用空白字段保留默认值，以及在会话或加载参数变化时自动重建相应对象。

## 上游 audio.cpp 简介

上游 audio.cpp 提供 149 个音频模型家族和 250 多个变体，覆盖 TTS、ASR、声音克隆、语音转换、分离、音乐生成、对齐等任务。共享运行时包含 CLI、兼容 OpenAI 的服务路由、内置 WebUI、GGUF 模型包、可复用会话和多种硬件后端。

Android 移植不代表所有桌面模型都适合在手机上运行。模型覆盖、内存需求、可选依赖和后端支持仍取决于具体模型。

### 桌面构建快速入口

Linux：

```bash
scripts/build_linux.sh --deployment-build --backend cpu --target audiocpp_cli --target audiocpp_server
scripts/build_linux.sh --deployment-build --backend cuda --target audiocpp_cli --target audiocpp_server
```

Windows PowerShell：

```powershell
.\scripts\build_windows.ps1 -DeploymentBuild
.\scripts\build_windows.ps1 -Preset windows-cuda-release -Target audiocpp_cli -DeploymentBuild
```

macOS / Metal：

```bash
scripts/build_metal.sh --deployment-build --target audiocpp_cli
```

桌面预编译包见[上游 Releases](https://github.com/0xShug0/audio.cpp/releases)，完整后端要求见下方文档链接。

### 模型与 CLI

使用原生模型管理器安装模型包：

```bash
audiocpp_model_manager list
audiocpp_model_manager install qwen3_asr_0_6b_q8_0 --models-root models
```

运行 TTS 请求：

```bash
build/bin/audiocpp_cli \
  --task tts \
  --family moss_tts_nano \
  --model /path/to/model.gguf \
  --backend cpu \
  --text "Hello from audio.cpp." \
  --out build/out/tts.wav
```

执行模型命令时请始终指定 `--family`。模型专有设置分别通过 `--load-option`、`--session-option` 和 `--request-option` 传入。不要假设不同模型共享同一组参数，应检查已加载模型实际开放的选项：

```bash
build/bin/audiocpp_cli --model /path/to/model.gguf --family moss_tts_nano --help
build/bin/audiocpp_cli --model /path/to/model.gguf --family moss_tts_nano --inspect
```

详细说明见 [CLI 与模型使用指南](docs/usage.md)、[模型管理指南](docs/model_manager.md)和 [GGUF 指南](docs/gguf.md)。

### Server 和 WebUI

```bash
audiocpp_server --ui --ui-management --backend cuda
```

打开 `http://127.0.0.1:8080`。Server 和 WebUI 属于上游桌面能力；Android Demo 直接使用进程内 C API。配置和 API 路由见 [Server 指南](app/server/README.md)。

## 文档导航

| 文档 | 内容 |
|---|---|
| [Android 构建与集成](docs/build/android.md) | NDK 构建、打包、JNI、模型资产和设备冒烟测试 |
| [C API](docs/c_api.md) | 句柄生命周期、ABI 兼容、流式接口、日志和动态参数发现 |
| [CLI 与模型](docs/usage.md) | 任务、选项、批处理和模型文档索引 |
| [模型管理器](docs/model_manager.md) | 模型目录和安装 |
| [GGUF](docs/gguf.md) | 模型包、精度和转换 |
| [桌面构建](docs/build/) | Linux、Windows、HIP、WebGPU、SYCL 等平台指南 |
| [贡献指南](CONTRIBUTING.md) | 开发、验证和新模型移植流程 |

## 注意事项

- GGUF 是容器格式，不是通用架构适配器。只有张量和元数据匹配 audio.cpp 模型实现时，其他项目的 GGUF 才能使用。
- 模型权重保留原始许可证，详见[模型许可证说明](docs/model_licenses.md)。
- 上游新闻、完整支持模型表、性能报告和社区项目请查阅[原 audio.cpp 仓库](https://github.com/0xShug0/audio.cpp)。
