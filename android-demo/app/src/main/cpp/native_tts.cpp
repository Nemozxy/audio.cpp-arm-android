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

namespace {

std::mutex g_mutex;
audiocpp_registry * g_registry = nullptr;
audiocpp_model * g_model = nullptr;
audiocpp_session * g_session = nullptr;

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

void ensure_session(const char * model_path, int threads) {
    if (g_session != nullptr) {
        return;
    }
    // std::filesystem::temp_directory_path() otherwise defaults to the
    // app-inaccessible /data/local/tmp on Android. Embedded GGUF sidecars
    // must be extracted below the app's private model directory instead.
    std::string temp_dir(model_path);
    const size_t separator = temp_dir.find_last_of('/');
    if (separator != std::string::npos) {
        temp_dir.resize(separator);
    }
    if (temp_dir.empty() || setenv("TMPDIR", temp_dir.c_str(), 1) != 0) {
        throw std::runtime_error("无法设置应用私有临时目录");
    }
    audiocpp_log_set(log_callback, nullptr);
    if (g_registry == nullptr) {
        check(audiocpp_registry_create(nullptr, &g_registry), "创建模型注册表");
    }
    if (g_model == nullptr) {
        audiocpp_model_config config = {"moss_tts_nano", nullptr, nullptr, nullptr};
        check(audiocpp_model_load(g_registry, model_path, &config, nullptr, &g_model),
              "加载 MOSS-TTS-Nano 模型");
    }
    audiocpp_backend_config backend = {"cpu", 0, std::max(1, threads)};
    check(audiocpp_session_create(g_model, "tts", "offline", &backend, nullptr, &g_session),
          "创建 CPU TTS 会话");
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

jstring java_string(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_audiocpp_ttsdemo_NativeTts_nativeVersion(JNIEnv * env, jclass) {
    return env->NewStringUTF(audiocpp_build_version());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_audiocpp_ttsdemo_NativeTts_synthesize(
    JNIEnv * env, jclass, jstring model_path, jstring text, jstring output_path, jint threads) {
    UtfChars model(env, model_path);
    UtfChars transcript(env, text);
    UtfChars output(env, output_path);
    if (model.value == nullptr || transcript.value == nullptr || output.value == nullptr) {
        return java_string(env, "JNI 参数为空或内存不足");
    }

    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        ensure_session(model.value, threads);

        RequestGuard request;
        if (request.value == nullptr) {
            throw std::runtime_error("创建合成请求失败");
        }
        check(audiocpp_request_set_text(request.value, transcript.value, nullptr),
              "设置合成文字");

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
