#include <jni.h>
#include <android/log.h>

#include "audiocpp.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <string>
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
}

void ensure_session(const char * model_path, int threads, const std::vector<OptionInput> & inputs) {
    ensure_model(model_path, inputs);
    const std::string signature = option_signature(inputs, AUDIOCPP_OPTION_SCOPE_SESSION, threads);
    if (g_session != nullptr && g_session_signature == signature) {
        return;
    }

    clear_session();
    OptionsGuard session_options;
    apply_options(session_options.value, inputs, AUDIOCPP_OPTION_SCOPE_SESSION);
    audiocpp_backend_config backend = {"cpu", 0, std::max(1, threads)};
    check(audiocpp_session_create(g_model, "tts", "offline", &backend,
                                  session_options.value, &g_session),
          "创建 CPU TTS 会话");
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
    if (audiocpp_model_supports(g_model, "tts", "offline") != 1) {
        throw std::runtime_error("当前模型不支持离线 TTS，无法用于此 Demo");
    }

    std::string json = "{";
    append_json_name(json, "family");
    json += json_string(audiocpp_model_family(g_model));
    append_json_name(json, "description");
    json += json_string(audiocpp_model_description(g_model));
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
        __android_log_print(ANDROID_LOG_ERROR, "AudioCppNative", "%s", error.what());
        throw_java(env, error.what());
    } catch (...) {
        throw_java(env, "读取模型参数时发生未知错误");
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_audiocpp_ttsdemo_NativeTts_synthesize(
    JNIEnv * env, jclass, jstring model_path, jstring text, jstring output_path, jint threads,
    jintArray scopes, jobjectArray keys, jobjectArray values) {
    UtfChars model(env, model_path);
    UtfChars transcript(env, text);
    UtfChars output(env, output_path);
    if (model.value == nullptr || transcript.value == nullptr || output.value == nullptr) {
        return java_string(env, "JNI 参数为空或内存不足");
    }

    try {
        const auto inputs = read_options(env, scopes, keys, values);
        std::lock_guard<std::mutex> lock(g_mutex);
        ensure_session(model.value, threads, inputs);

        RequestGuard request;
        if (request.value == nullptr) {
            throw std::runtime_error("创建合成请求失败");
        }
        check(audiocpp_request_set_text(request.value, transcript.value, nullptr),
              "设置合成文字");
        for (const auto & input : inputs) {
            if (input.scope == AUDIOCPP_OPTION_SCOPE_REQUEST) {
                check(audiocpp_request_set_option(request.value, input.key.c_str(), input.value.c_str()),
                      "设置请求参数");
            }
        }

        ResultGuard result;
        check(audiocpp_session_run(g_session, request.value, &result.value), "执行 TTS 推理");

        const float * samples = nullptr;
        size_t frames = 0;
        int sample_rate = 0;
        int channels = 0;
        check(audiocpp_result_audio(result.value, &samples, &frames, &sample_rate, &channels),
              "读取合成音频");
        write_wav(output.value, samples, frames, sample_rate, channels);
        __android_log_print(ANDROID_LOG_INFO, "AudioCppNative",
                            "WAV written: %zu frames, %d Hz, %d channels",
                            frames, sample_rate, channels);
        return nullptr;
    } catch (const std::exception & error) {
        __android_log_print(ANDROID_LOG_ERROR, "AudioCppNative", "%s", error.what());
        return java_string(env, error.what());
    } catch (...) {
        return java_string(env, "原生合成发生未知错误");
    }
}
