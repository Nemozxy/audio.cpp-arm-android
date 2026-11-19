#include <jni.h>
#include <android/log.h>

#include "audiocpp.h"
#include "engine/framework/audio/wav_reader.h"
#include "ggml-cpu.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace {

std::mutex g_mutex;
audiocpp_registry * g_registry = nullptr;
audiocpp_model * g_model = nullptr;
audiocpp_session * g_session = nullptr;
std::string g_model_path;
std::string g_load_signature;
std::string g_session_signature;

struct OptionInput {
    int scope = AUDIOCPP_OPTION_SCOPE_REQUEST;
    std::string key;
    std::string value;
};

std::vector<float> convert_asr_wav_to_16k_mono(const engine::audio::WavData & wav) {
    if (wav.sample_rate <= 0 || wav.channels <= 0 || wav.samples.empty() ||
        wav.samples.size() % static_cast<size_t>(wav.channels) != 0) {
        throw std::runtime_error("输入音频没有有效的 PCM 内容");
    }
    const size_t input_frames = wav.samples.size() / static_cast<size_t>(wav.channels);
    std::vector<float> mono(input_frames, 0.0F);
    for (size_t frame = 0; frame < input_frames; ++frame) {
        double sum = 0.0;
        for (int channel = 0; channel < wav.channels; ++channel) {
            sum += wav.samples[frame * static_cast<size_t>(wav.channels) + channel];
        }
        mono[frame] = static_cast<float>(sum / wav.channels);
    }
    if (wav.sample_rate == 16000) {
        return mono;
    }

    constexpr int target_rate = 16000;
    const double scale = static_cast<double>(target_rate) / wav.sample_rate;
    const size_t output_frames = static_cast<size_t>(std::llround(input_frames * scale));
    std::vector<float> output(output_frames, 0.0F);
    for (size_t frame = 0; frame < output_frames; ++frame) {
        const double position = frame / scale;
        const size_t left = static_cast<size_t>(std::floor(position));
        const size_t right = std::min(left + 1, input_frames - 1);
        const float fraction = static_cast<float>(position - left);
        output[frame] = mono[left] * (1.0F - fraction) + mono[right] * fraction;
    }
    return output;
}

std::string status_error(audiocpp_status status, const char * operation) {
    std::string message = operation;
    message += ": ";
    message += audiocpp_status_string(status);
    const char * detail = audiocpp_last_error();
    if (detail != nullptr && *detail != '\0') {
        message += " — ";
        message += detail;
    }
    return message;
}

void check(audiocpp_status status, const char * operation) {
    if (status != AUDIOCPP_OK) {
        throw std::runtime_error(status_error(status, operation));
    }
}

std::mutex g_diagnostic_mutex;
std::string g_diagnostic_log;
bool g_diagnostic_truncated = false;
constexpr size_t kDiagnosticLogLimit = 4 * 1024 * 1024;

void log_callback(audiocpp_log_level level, const char * text, void *);

void append_diagnostic_log(const char * text) {
    if (text == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_diagnostic_mutex);
    if (g_diagnostic_log.size() >= kDiagnosticLogLimit) {
        if (!g_diagnostic_truncated) {
            g_diagnostic_log += "[log truncated after 4 MiB]\n";
            g_diagnostic_truncated = true;
        }
        return;
    }
    g_diagnostic_log += text;
    if (!g_diagnostic_log.empty() && g_diagnostic_log.back() != '\n') {
        g_diagnostic_log += '\n';
    }
    if (g_diagnostic_log.size() > kDiagnosticLogLimit) {
        g_diagnostic_log.resize(kDiagnosticLogLimit);
        g_diagnostic_log += "\n[log truncated after 4 MiB]\n";
        g_diagnostic_truncated = true;
    }
}

void native_log(int priority, const char * tag, const char * format, ...) {
    va_list arguments;
    va_start(arguments, format);
    va_list measure;
    va_copy(measure, arguments);
    const int length = vsnprintf(nullptr, 0, format, measure);
    va_end(measure);
    if (length >= 0) {
        std::vector<char> message(static_cast<size_t>(length) + 1);
        vsnprintf(message.data(), message.size(), format, arguments);
        __android_log_write(priority, tag, message.data());
        append_diagnostic_log(message.data());
    }
    va_end(arguments);
}

void begin_diagnostic_log(const char * task, int threads) {
    {
        std::lock_guard<std::mutex> lock(g_diagnostic_mutex);
        g_diagnostic_log.clear();
        g_diagnostic_truncated = false;
    }
    // Install the sink before diagnostics so cold runs also retain HWCAP data.
    audiocpp_log_set(log_callback, nullptr);
    audiocpp_cpu_profile_reset();
    native_log(ANDROID_LOG_INFO, "AudioCppNative", "Run started: task=%s threads=%d profiling=%d",
               task, threads, audiocpp_cpu_profile_is_enabled());
    audiocpp_cpu_log_diagnostics();
}

class ProfileRunGuard {
public:
    ProfileRunGuard() : started_(std::chrono::steady_clock::now()) {
        if (audiocpp_cpu_profile_is_enabled() == 0) {
            return;
        }
        sampler_ = std::thread([this]() { sample_loop(); });
    }

    ~ProfileRunGuard() {
        if (sampler_.joinable()) {
            {
                std::lock_guard<std::mutex> lock(sample_mutex_);
                stop_ = true;
            }
            sample_cv_.notify_all();
            sampler_.join();
        }
        audiocpp_cpu_profile_log_summary();
    }

    ProfileRunGuard(const ProfileRunGuard &) = delete;
    ProfileRunGuard & operator=(const ProfileRunGuard &) = delete;

private:
    void sample_loop() {
        std::unique_lock<std::mutex> lock(sample_mutex_);
        while (!stop_) {
            lock.unlock();
            sample_cpu_frequencies();
            lock.lock();
            sample_cv_.wait_for(lock, std::chrono::seconds(1), [this]() { return stop_; });
        }
    }

    void sample_cpu_frequencies() {
        std::string values;
        for (int cpu = 0; cpu < 64; ++cpu) {
            const std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(cpu) +
                "/cpufreq/scaling_cur_freq";
            std::ifstream input(path);
            int64_t frequency = 0;
            if (!(input >> frequency)) {
                continue;
            }
            if (!values.empty()) {
                values += ' ';
            }
            values += "cpu" + std::to_string(cpu) + "=" + std::to_string(frequency);
        }
        const double elapsed = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - started_).count();
        native_log(ANDROID_LOG_INFO, "AudioCppNative", "cpu.freq.sample elapsed_ms=%.3f unit=kHz %s",
                   elapsed, values.empty() ? "unavailable" : values.c_str());
    }

    std::chrono::steady_clock::time_point started_;
    std::thread sampler_;
    std::mutex sample_mutex_;
    std::condition_variable sample_cv_;
    bool stop_ = false;
};

void log_callback(audiocpp_log_level level, const char * text, void *) {
    int priority = ANDROID_LOG_DEBUG;
    switch (level) {
        case AUDIOCPP_LOG_LEVEL_INFO: priority = ANDROID_LOG_INFO; break;
        case AUDIOCPP_LOG_LEVEL_WARN: priority = ANDROID_LOG_WARN; break;
        case AUDIOCPP_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        default: break;
    }
    if (text != nullptr) {
        __android_log_write(priority, "AudioCppNative", text);
        append_diagnostic_log(text);
    }
}

std::string option_signature(const std::vector<OptionInput> & inputs, int scope, int threads = 0) {
    std::vector<std::pair<std::string, std::string>> sorted;
    for (const auto & input : inputs) {
        if (input.scope == scope) {
            sorted.emplace_back(input.key, input.value);
        }
    }
    std::sort(sorted.begin(), sorted.end());
    std::string signature = std::to_string(threads) + ";";
    for (const auto & item : sorted) {
        signature += std::to_string(item.first.size()) + ":" + item.first;
        signature += std::to_string(item.second.size()) + ":" + item.second + ";";
    }
    return signature;
}

struct OptionsGuard {
    audiocpp_options * value = audiocpp_options_create();
    ~OptionsGuard() { audiocpp_options_free(value); }
};

void apply_options(audiocpp_options * destination,
                   const std::vector<OptionInput> & inputs, int scope) {
    if (destination == nullptr) {
        throw std::runtime_error("创建模型参数集合失败");
    }
    for (const auto & input : inputs) {
        if (input.scope == scope) {
            check(audiocpp_options_set(destination, input.key.c_str(), input.value.c_str()),
                  "设置模型参数");
        }
    }
}

void clear_session() {
    audiocpp_session_free(g_session);
    g_session = nullptr;
    g_session_signature.clear();
}

void clear_model() {
    clear_session();
    audiocpp_model_free(g_model);
    g_model = nullptr;
    g_model_path.clear();
    g_load_signature.clear();
}

void initialize_temp_directory(const char * model_path) {
    // Embedded GGUF sidecars must not use the app-inaccessible /data/local/tmp.
    std::string temp_dir(model_path);
    const size_t separator = temp_dir.find_last_of('/');
    if (separator != std::string::npos) {
        temp_dir.resize(separator);
    }
    if (temp_dir.empty() || setenv("TMPDIR", temp_dir.c_str(), 1) != 0) {
        throw std::runtime_error("无法设置应用私有临时目录");
    }
}

void ensure_model(const char * model_path, const std::vector<OptionInput> & inputs) {
    const std::string signature = option_signature(inputs, AUDIOCPP_OPTION_SCOPE_LOAD);
    if (g_model != nullptr && g_model_path == model_path && g_load_signature == signature) {
        return;
    }

    const auto started = std::chrono::steady_clock::now();
    clear_model();
    initialize_temp_directory(model_path);
    audiocpp_log_set(log_callback, nullptr);
    if (g_registry == nullptr) {
        check(audiocpp_registry_create(nullptr, &g_registry), "创建模型注册表");
    }

    OptionsGuard load_options;
    apply_options(load_options.value, inputs, AUDIOCPP_OPTION_SCOPE_LOAD);
    // A null family hint asks the registry to identify the model from its path.
    // No family-specific model selection is duplicated in this JNI binding.
    audiocpp_model_config config = {nullptr, nullptr, nullptr, nullptr};
    check(audiocpp_model_load(g_registry, model_path, &config, load_options.value, &g_model),
          "自动识别并加载模型");
    g_model_path = model_path;
    g_load_signature = signature;
    const double elapsed = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - started).count();
    native_log(ANDROID_LOG_INFO, "AudioCppNative",
                        "Model loaded: %.3f ms", elapsed);
}

void ensure_session(const char * model_path, const char * task, int threads,
                    const char * backend_name, const std::vector<OptionInput> & inputs) {
    ensure_model(model_path, inputs);
    if (audiocpp_model_supports(g_model, task, "offline") != 1) {
        throw std::runtime_error(std::string("当前模型不支持离线任务：") + task);
    }
    if (backend_name == nullptr ||
        (std::string(backend_name) != "cpu" && std::string(backend_name) != "vulkan")) {
        throw std::runtime_error("Demo 仅支持显式选择 cpu 或 vulkan 后端");
    }
    const std::string signature = std::string(task) + ";" + backend_name + ";" +
        option_signature(inputs, AUDIOCPP_OPTION_SCOPE_SESSION, threads);
    if (g_session != nullptr && g_session_signature == signature) {
        return;
    }

    const auto started = std::chrono::steady_clock::now();
    clear_session();
    OptionsGuard session_options;
    apply_options(session_options.value, inputs, AUDIOCPP_OPTION_SCOPE_SESSION);
    audiocpp_backend_config backend = {backend_name, 0, std::max(1, threads)};
    check(audiocpp_session_create(g_model, task, "offline", &backend,
                                  session_options.value, &g_session),
          "创建语音会话");
    const double elapsed = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - started).count();
    native_log(ANDROID_LOG_INFO, "AudioCppNative",
               "Session created: %s/offline, backend=%s device=0 %.3f ms, threads=%d",
               task, backend_name, elapsed, threads);
    g_session_signature = signature;
}

void write_u16(std::ofstream & output, uint16_t value) {
    const char bytes[] = {
        static_cast<char>(value & 0xff),
        static_cast<char>((value >> 8) & 0xff),
    };
    output.write(bytes, sizeof(bytes));
}

void write_u32(std::ofstream & output, uint32_t value) {
    const char bytes[] = {
        static_cast<char>(value & 0xff),
        static_cast<char>((value >> 8) & 0xff),
        static_cast<char>((value >> 16) & 0xff),
        static_cast<char>((value >> 24) & 0xff),
    };
    output.write(bytes, sizeof(bytes));
}

void write_wav(const char * output_path, const float * samples, size_t frames,
               int sample_rate, int channels) {
    if (samples == nullptr || frames == 0 || sample_rate <= 0 ||
        channels <= 0 || channels > std::numeric_limits<uint16_t>::max()) {
        throw std::runtime_error("模型返回了无效的 PCM 音频");
    }
    if (frames > (std::numeric_limits<uint32_t>::max() - 36u) /
                 (static_cast<size_t>(channels) * sizeof(int16_t))) {
        throw std::runtime_error("生成的 WAV 超出 RIFF 大小限制");
    }

    const uint32_t data_size = static_cast<uint32_t>(
        frames * static_cast<size_t>(channels) * sizeof(int16_t));
    const std::string temporary_path = std::string(output_path) + ".tmp";
    std::ofstream output(temporary_path, std::ios::binary | std::ios::trunc);
    if (!output) {
        throw std::runtime_error("无法创建 WAV 临时文件");
    }

    output.write("RIFF", 4);
    write_u32(output, 36u + data_size);
    output.write("WAVEfmt ", 8);
    write_u32(output, 16);
    write_u16(output, 1);
    write_u16(output, static_cast<uint16_t>(channels));
    write_u32(output, static_cast<uint32_t>(sample_rate));
    write_u32(output, static_cast<uint32_t>(sample_rate * channels * sizeof(int16_t)));
    write_u16(output, static_cast<uint16_t>(channels * sizeof(int16_t)));
    write_u16(output, 16);
    output.write("data", 4);
    write_u32(output, data_size);

    const size_t sample_count = frames * static_cast<size_t>(channels);
    for (size_t index = 0; index < sample_count; ++index) {
        const float sample = std::isfinite(samples[index])
            ? std::max(-1.0f, std::min(1.0f, samples[index])) : 0.0f;
        const int16_t pcm = static_cast<int16_t>(std::lrintf(sample * 32767.0f));
        write_u16(output, static_cast<uint16_t>(pcm));
    }
    output.close();
    if (!output) {
        std::remove(temporary_path.c_str());
        throw std::runtime_error("写入 WAV 音频失败");
    }
    std::remove(output_path);
    if (std::rename(temporary_path.c_str(), output_path) != 0) {
        std::remove(temporary_path.c_str());
        throw std::runtime_error("无法保存最终 WAV 文件");
    }
}

struct RequestGuard {
    audiocpp_request * value = audiocpp_request_create();
    ~RequestGuard() { audiocpp_request_free(value); }
};

struct ResultGuard {
    audiocpp_result * value = nullptr;
    ~ResultGuard() { audiocpp_result_free(value); }
};

struct UtfChars {
    JNIEnv * env;
    jstring source;
    const char * value;

    UtfChars(JNIEnv * env, jstring source) : env(env), source(source), value(nullptr) {
        if (source != nullptr) {
            value = env->GetStringUTFChars(source, nullptr);
        }
    }
    ~UtfChars() {
        if (value != nullptr) {
            env->ReleaseStringUTFChars(source, value);
        }
    }
};

struct IntArray {
    JNIEnv * env;
    jintArray source;
    jint * value;

    IntArray(JNIEnv * env, jintArray source) : env(env), source(source), value(nullptr) {
        if (source != nullptr) {
            value = env->GetIntArrayElements(source, nullptr);
        }
    }
    ~IntArray() {
        if (value != nullptr) {
            env->ReleaseIntArrayElements(source, value, JNI_ABORT);
        }
    }
};

std::vector<OptionInput> read_options(JNIEnv * env, jintArray scopes,
                                      jobjectArray keys, jobjectArray values) {
    if (scopes == nullptr || keys == nullptr || values == nullptr) {
        throw std::invalid_argument("模型参数数组不能为空");
    }
    const jsize count = env->GetArrayLength(scopes);
    if (env->GetArrayLength(keys) != count || env->GetArrayLength(values) != count) {
        throw std::invalid_argument("模型参数数组长度不一致");
    }

    IntArray scope_values(env, scopes);
    if (count > 0 && scope_values.value == nullptr) {
        throw std::runtime_error("无法读取模型参数作用域");
    }
    std::vector<OptionInput> inputs;
    inputs.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        const int scope = scope_values.value[index];
        if (scope != AUDIOCPP_OPTION_SCOPE_REQUEST &&
            scope != AUDIOCPP_OPTION_SCOPE_SESSION &&
            scope != AUDIOCPP_OPTION_SCOPE_LOAD) {
            throw std::invalid_argument("未知的模型参数作用域");
        }
        auto key = static_cast<jstring>(env->GetObjectArrayElement(keys, index));
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        UtfChars key_chars(env, key);
        UtfChars value_chars(env, value);
        if (key_chars.value == nullptr || value_chars.value == nullptr || *key_chars.value == '\0') {
            throw std::invalid_argument("模型参数名称或值为空");
        }
        inputs.push_back({scope, key_chars.value, value_chars.value});
        native_log(ANDROID_LOG_INFO, "AudioCppNative", "Option: scope=%d %s=%s",
                   scope, key_chars.value, value_chars.value);
    }
    return inputs;
}

std::string json_escape(const char * text) {
    static const char hex[] = "0123456789abcdef";
    std::string escaped;
    if (text == nullptr) {
        return escaped;
    }
    for (const unsigned char c : std::string(text)) {
        switch (c) {
            case '"': escaped += "\\\""; break;
            case '\\': escaped += "\\\\"; break;
            case '\b': escaped += "\\b"; break;
            case '\f': escaped += "\\f"; break;
            case '\n': escaped += "\\n"; break;
            case '\r': escaped += "\\r"; break;
            case '\t': escaped += "\\t"; break;
            default:
                if (c < 0x20) {
                    escaped += "\\u00";
                    escaped += hex[(c >> 4) & 0xf];
                    escaped += hex[c & 0xf];
                } else {
                    escaped += static_cast<char>(c);
                }
        }
    }
    return escaped;
}

std::string json_string(const char * text) {
    return "\"" + json_escape(text) + "\"";
}

void append_json_name(std::string & json, const char * name) {
    if (json.back() != '{' && json.back() != '[') {
        json += ',';
    }
    json += json_string(name);
    json += ':';
}

std::string model_inspection_json() {
    const bool supports_tts = audiocpp_model_supports(g_model, "tts", "offline") == 1;
    const bool supports_clon = audiocpp_model_supports(g_model, "clon", "offline") == 1;
    const bool supports_asr = audiocpp_model_supports(g_model, "asr", "offline") == 1;
    if (!supports_tts && !supports_clon && !supports_asr) {
        throw std::runtime_error("当前模型不支持此 Demo 的离线 TTS、语音克隆或 ASR 任务");
    }

    std::string json = "{";
    append_json_name(json, "family");
    json += json_string(audiocpp_model_family(g_model));
    append_json_name(json, "description");
    json += json_string(audiocpp_model_description(g_model));
    append_json_name(json, "supportsTts");
    json += supports_tts ? "true" : "false";
    append_json_name(json, "supportsClon");
    json += supports_clon ? "true" : "false";
    append_json_name(json, "supportsAsr");
    json += supports_asr ? "true" : "false";
    append_json_name(json, "supportsSpeakerReference");
    json += audiocpp_model_supports_speaker_reference(g_model) == 1 ? "true" : "false";
    append_json_name(json, "supportsStyleCondition");
    json += audiocpp_model_supports_style_condition(g_model) == 1 ? "true" : "false";

    append_json_name(json, "languages");
    json += '[';
    const size_t language_count = audiocpp_model_language_count(g_model);
    for (size_t index = 0; index < language_count; ++index) {
        const char * language = nullptr;
        check(audiocpp_model_language(g_model, index, &language), "读取模型语言");
        if (index > 0) json += ',';
        json += json_string(language);
    }
    json += ']';

    append_json_name(json, "options");
    json += '[';
    bool first = true;
    const int scopes[] = {
        AUDIOCPP_OPTION_SCOPE_REQUEST,
        AUDIOCPP_OPTION_SCOPE_SESSION,
        AUDIOCPP_OPTION_SCOPE_LOAD,
    };
    for (const int scope : scopes) {
        const auto option_scope = static_cast<audiocpp_option_scope>(scope);
        const size_t count = audiocpp_model_option_count(g_model, option_scope);
        for (size_t index = 0; index < count; ++index) {
            const char * name = nullptr;
            const char * value_name = nullptr;
            const char * description = nullptr;
            const char * default_value = nullptr;
            const char * min_value = nullptr;
            const char * max_value = nullptr;
            int required = 0;
            check(audiocpp_model_option(g_model, option_scope, index, &name, &value_name,
                                        &description, &default_value, &min_value, &max_value,
                                        &required),
                  "读取模型参数元数据");
            if (!first) json += ',';
            first = false;
            json += '{';
            append_json_name(json, "scope");
            json += std::to_string(scope);
            append_json_name(json, "name");
            json += json_string(name);
            append_json_name(json, "valueName");
            json += json_string(value_name);
            append_json_name(json, "description");
            json += json_string(description);
            append_json_name(json, "defaultValue");
            json += json_string(default_value);
            append_json_name(json, "minValue");
            json += json_string(min_value);
            append_json_name(json, "maxValue");
            json += json_string(max_value);
            append_json_name(json, "required");
            json += required != 0 ? "true" : "false";
            json += '}';
        }
    }
    json += "]}";
    return json;
}

jstring java_string(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

void throw_java(JNIEnv * env, const std::string & message) {
    auto error_class = env->FindClass("java/lang/IllegalStateException");
    if (error_class != nullptr) {
        env->ThrowNew(error_class, message.c_str());
    }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_audiocpp_ttsdemo_NativeTts_nativeVersion(JNIEnv * env, jclass) {
    return env->NewStringUTF(audiocpp_build_version());
}

extern "C" JNIEXPORT void JNICALL
Java_com_audiocpp_ttsdemo_NativeTts_setProfiling(JNIEnv *, jclass, jboolean enabled) {
    const bool profiling = enabled == JNI_TRUE;
    audiocpp_cpu_profile_set_enabled(profiling ? 1 : 0);
    // ggml reads these once when the Vulkan instance is initialized. GPU
    // timestamp diagnostics go to logcat and are never enabled for plain runs.
    if (profiling) {
        setenv("GGML_VK_PERF_LOGGER", "1", 1);
        setenv("GGML_VK_SYNC_LOGGER", "1", 1);
    } else {
        unsetenv("GGML_VK_PERF_LOGGER");
        unsetenv("GGML_VK_SYNC_LOGGER");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_audiocpp_ttsdemo_NativeTts_performanceReport(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> lock(g_diagnostic_mutex);
    return java_string(env, g_diagnostic_log);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_audiocpp_ttsdemo_NativeTts_inspectModel(JNIEnv * env, jclass, jstring model_path) {
    UtfChars model(env, model_path);
    if (model.value == nullptr) {
        throw_java(env, "模型路径为空或内存不足");
        return nullptr;
    }
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        ensure_model(model.value, {});
        return java_string(env, model_inspection_json());
    } catch (const std::exception & error) {
        native_log(ANDROID_LOG_ERROR, "AudioCppNative", "%s", error.what());
        throw_java(env, error.what());
    } catch (...) {
        throw_java(env, "读取模型参数时发生未知错误");
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_audiocpp_ttsdemo_NativeTts_synthesize(
    JNIEnv * env, jclass, jstring model_path, jstring text, jstring output_path,
    jstring reference_audio_path, jint threads, jstring backend_name,
    jintArray scopes, jobjectArray keys, jobjectArray values) {
    UtfChars model(env, model_path);
    UtfChars transcript(env, text);
    UtfChars output(env, output_path);
    UtfChars reference_audio(env, reference_audio_path);
    UtfChars backend(env, backend_name);
    if (model.value == nullptr || transcript.value == nullptr || output.value == nullptr) {
        return java_string(env, "JNI 参数为空或内存不足");
    }

    try {
        const auto total_started = std::chrono::steady_clock::now();
        begin_diagnostic_log("tts", threads);
        ProfileRunGuard profile_run;
        const auto inputs = read_options(env, scopes, keys, values);
        const bool has_reference = reference_audio.value != nullptr && *reference_audio.value != '\0';
        std::lock_guard<std::mutex> lock(g_mutex);
        ensure_model(model.value, inputs);
        if (has_reference && audiocpp_model_supports_speaker_reference(g_model) != 1) {
            throw std::runtime_error("当前模型不支持参考音频语音克隆");
        }
        const char * task = has_reference ? "clon" :
            (audiocpp_model_supports(g_model, "tts", "offline") == 1 ? "tts" : "clon");
        native_log(ANDROID_LOG_INFO, "AudioCppNative", "Resolved task: %s/offline", task);
        const auto session_started = std::chrono::steady_clock::now();
        ensure_session(model.value, task, threads, backend.value, inputs);
        const double session_ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - session_started).count();

        RequestGuard request;
        if (request.value == nullptr) {
            throw std::runtime_error("创建合成请求失败");
        }
        check(audiocpp_request_set_text(request.value, transcript.value, nullptr),
              "设置合成文字");
        if (has_reference) {
            const auto wav = engine::audio::read_wav_f32(std::filesystem::path(reference_audio.value));
            if (wav.samples.empty() || wav.channels <= 0 || wav.sample_rate <= 0) {
                throw std::runtime_error("参考音频没有有效的 PCM 内容");
            }
            const size_t reference_frames = wav.samples.size() / static_cast<size_t>(wav.channels);
            check(audiocpp_request_set_voice_audio(request.value, wav.samples.data(),
                                                   reference_frames, wav.sample_rate, wav.channels),
                  "设置语音克隆参考音频");
            native_log(ANDROID_LOG_INFO, "AudioCppNative",
                                "Voice reference: %zu frames, %d Hz, %d channels",
                                reference_frames, wav.sample_rate, wav.channels);
        }
        for (const auto & input : inputs) {
            if (input.scope == AUDIOCPP_OPTION_SCOPE_REQUEST) {
                check(audiocpp_request_set_option(request.value, input.key.c_str(), input.value.c_str()),
                      "设置请求参数");
            }
        }

        const auto inference_started = std::chrono::steady_clock::now();
        ResultGuard result;
        check(audiocpp_session_run(g_session, request.value, &result.value), "执行语音合成推理");
        const double inference_ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - inference_started).count();

        const float * samples = nullptr;
        size_t frames = 0;
        int sample_rate = 0;
        int channels = 0;
        check(audiocpp_result_audio(result.value, &samples, &frames, &sample_rate, &channels),
              "读取合成音频");
        write_wav(output.value, samples, frames, sample_rate, channels);
        native_log(ANDROID_LOG_INFO, "AudioCppNative",
                            "WAV written: %zu frames, %d Hz, %d channels",
                            frames, sample_rate, channels);
        const double total_ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - total_started).count();
        const double output_seconds = sample_rate > 0 ? static_cast<double>(frames) / sample_rate : 0.0;
        native_log(ANDROID_LOG_INFO, "AudioCppNative",
                   "TTS timing: session=%.3f ms inference=%.3f ms total=%.3f ms output=%.3f s rtf=%.6f threads=%d",
                   session_ms, inference_ms, total_ms, output_seconds,
                   output_seconds > 0 ? total_ms / (output_seconds * 1000.0) : 0.0, threads);
        return nullptr;
    } catch (const std::exception & error) {
        native_log(ANDROID_LOG_ERROR, "AudioCppNative", "%s", error.what());
        return java_string(env, error.what());
    } catch (...) {
        return java_string(env, "原生合成发生未知错误");
    }
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_audiocpp_ttsdemo_NativeTts_transcribe(
    JNIEnv * env, jclass, jstring model_path, jstring input_audio_path, jint threads,
    jstring backend_name, jintArray scopes, jobjectArray keys, jobjectArray values) {
    UtfChars model(env, model_path);
    UtfChars input_audio(env, input_audio_path);
    UtfChars backend(env, backend_name);
    if (model.value == nullptr || input_audio.value == nullptr) {
        throw_java(env, "JNI 参数为空或内存不足");
        return nullptr;
    }

    try {
        const auto total_started = std::chrono::steady_clock::now();
        begin_diagnostic_log("asr", threads);
        auto elapsed_ms = [](std::chrono::steady_clock::time_point started) {
            return std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - started).count();
        };
        auto stage_started = total_started;
        ProfileRunGuard profile_run;
        const auto inputs = read_options(env, scopes, keys, values);
        const auto wav = engine::audio::read_wav_f32(std::filesystem::path(input_audio.value));
        if (wav.samples.empty() || wav.channels <= 0 || wav.sample_rate <= 0) {
            throw std::runtime_error("输入音频没有有效的 PCM 内容");
        }
        const double wav_read_ms = elapsed_ms(stage_started);
        stage_started = std::chrono::steady_clock::now();
        const size_t input_frames = wav.samples.size() / static_cast<size_t>(wav.channels);
        const auto samples = convert_asr_wav_to_16k_mono(wav);
        const size_t frames = samples.size();
        const double convert_ms = elapsed_ms(stage_started);

        stage_started = std::chrono::steady_clock::now();
        std::lock_guard<std::mutex> lock(g_mutex);
        ensure_session(model.value, "asr", threads, backend.value, inputs);
        const double session_ms = elapsed_ms(stage_started);
        stage_started = std::chrono::steady_clock::now();
        RequestGuard request;
        if (request.value == nullptr) {
            throw std::runtime_error("创建识别请求失败");
        }
        check(audiocpp_request_set_audio(request.value, samples.data(), frames, 16000, 1),
              "设置识别音频");
        native_log(ANDROID_LOG_INFO, "AudioCppNative",
                            "ASR input: %zu frames, %d Hz, %d channels -> %zu frames, 16000 Hz, mono",
                            input_frames, wav.sample_rate, wav.channels, frames);
        for (const auto & input : inputs) {
            if (input.scope == AUDIOCPP_OPTION_SCOPE_REQUEST) {
                check(audiocpp_request_set_option(request.value, input.key.c_str(), input.value.c_str()),
                      "设置请求参数");
            }
        }
        const double request_ms = elapsed_ms(stage_started);

        stage_started = std::chrono::steady_clock::now();
        ResultGuard result;
        check(audiocpp_session_run(g_session, request.value, &result.value), "执行语音识别推理");
        const double inference_ms = elapsed_ms(stage_started);
        const char * text = nullptr;
        const char * language = nullptr;
        check(audiocpp_result_text(result.value, &text, &language), "读取识别文本");
        std::string json = "{";
        append_json_name(json, "text");
        json += json_string(text == nullptr ? "" : text);
        append_json_name(json, "language");
        json += json_string(language == nullptr ? "" : language);
        json += '}';
        native_log(ANDROID_LOG_INFO, "AudioCppNative",
                            "ASR result: %zu UTF-8 bytes", text == nullptr ? 0u : std::char_traits<char>::length(text));
        native_log(ANDROID_LOG_INFO, "AudioCppNative",
                            "ASR timing: wav_read=%.3f ms convert=%.3f ms session=%.3f ms request=%.3f ms inference=%.3f ms total=%.3f ms threads=%d",
                            wav_read_ms, convert_ms, session_ms, request_ms, inference_ms,
                            elapsed_ms(total_started), threads);
        return java_string(env, json);
    } catch (const std::exception & error) {
        native_log(ANDROID_LOG_ERROR, "AudioCppNative", "%s", error.what());
        throw_java(env, error.what());
    } catch (...) {
        throw_java(env, "原生识别发生未知错误");
    }
    return nullptr;
}
