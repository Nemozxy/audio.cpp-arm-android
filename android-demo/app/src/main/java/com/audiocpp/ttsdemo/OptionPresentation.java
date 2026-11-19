package com.audiocpp.ttsdemo;

/** Demo-specific wording and prominence. Controls and defaults still come from the model. */
record OptionPresentation(int label, int help, boolean common, boolean multiline, boolean positive) {
    private static final OptionPresentation GENERIC = new OptionPresentation(0, 0, false, false, false);
    private static final OptionPresentation SYNTHESIS_LANGUAGE = common(
            R.string.option_synthesis_language, R.string.option_synthesis_language_help);
    private static final OptionPresentation FRAME_LIMIT = common(
            R.string.option_audio_frame_limit, R.string.option_audio_frame_limit_help);
    private static final OptionPresentation TRANSCRIPT_LIMIT = advanced(
            R.string.option_transcript_limit, R.string.option_transcript_limit_help);

    private static OptionPresentation common(int label, int help) {
        return new OptionPresentation(label, help, true, false, false);
    }

    private static OptionPresentation advanced(int label, int help) {
        return new OptionPresentation(label, help, false, false, false);
    }

    static OptionPresentation forOption(String family, DemoModelProfile profile, ModelOption option) {
        if (option.scope != ModelOption.REQUEST) return GENERIC;
        if ("reference_text".equals(option.name)) {
            int help = switch (profile) {
                case QWEN_BASE -> R.string.option_reference_text_qwen_help;
                case BREEZE -> R.string.option_reference_text_breeze_help;
                case MOSS_TTS -> R.string.option_reference_text_help;
                default -> 0;
            };
            return multiline(R.string.option_reference_text, help);
        }
        return switch (profile) {
            case MOSS_TTS -> mossNano(option.name);
            case MOSS_ASR -> switch (option.name) {
                case "max_tokens" -> TRANSCRIPT_LIMIT;
                case "instruct" -> multiline(R.string.option_transcription_prompt,
                        R.string.option_transcription_prompt_help);
                default -> GENERIC;
            };
            case QWEN_ASR -> switch (option.name) {
                case "language" -> common(R.string.option_recognition_language,
                        R.string.option_recognition_language_help);
                case "max_tokens" -> TRANSCRIPT_LIMIT;
                case "audio_chunk_mode" -> common(R.string.option_audio_chunk_mode, R.string.option_audio_chunk_mode_help);
                case "audio_chunk_duration_sec" -> new OptionPresentation(R.string.option_audio_chunk_duration,
                        R.string.option_audio_chunk_duration_help, false, false, true);
                default -> GENERIC;
            };
            case QWEN_BASE, QWEN_CUSTOM, QWEN_DESIGN -> switch (option.name) {
                case "language" -> SYNTHESIS_LANGUAGE;
                case "voice_id" -> common(R.string.option_preset_voice, 0);
                case "instruct" -> multiline(profile == DemoModelProfile.QWEN_DESIGN
                        ? R.string.option_voice_description : R.string.option_voice_style, 0);
                case "x_vector_only_mode" -> common(R.string.option_embedding_only, R.string.option_embedding_only_help);
                case "max_tokens" -> FRAME_LIMIT;
                case "text_chunk_size" -> advanced(R.string.option_text_chunk_size, R.string.option_text_chunk_size_help);
                default -> GENERIC;
            };
            case INDEX -> switch (option.name) {
                case "language" -> SYNTHESIS_LANGUAGE;
                case "emotion_text" -> multiline(R.string.option_emotion_text, 0);
                case "emotion_alpha" -> common(R.string.option_emotion_strength, R.string.option_emotion_strength_help);
                case "duration_scale" -> new OptionPresentation(R.string.option_duration_scale,
                        R.string.option_duration_scale_help, true, false, true);
                case "max_tokens" -> common(R.string.option_speech_token_limit, R.string.option_speech_token_limit_help);
                case "interval_silence_ms" -> advanced(R.string.option_segment_pause, R.string.option_segment_pause_help);
                case "text_normalization" -> common(R.string.option_text_normalization, R.string.option_text_normalization_help);
                default -> GENERIC;
            };
            case BREEZE -> switch (option.name) {
                case "instruct" -> multiline(R.string.option_voice_description, 0);
                case "max_tokens" -> FRAME_LIMIT;
                case "text_chunk_size" -> advanced(R.string.option_text_chunk_size, R.string.option_text_chunk_size_help);
                case "text_chunk_mode" -> advanced(R.string.option_text_chunk_mode, R.string.option_text_chunk_mode_help);
                default -> GENERIC;
            };
            case GENERIC -> "kokoro_tts".equals(family) ? switch (option.name) {
                case "voice_id" -> common(R.string.option_voice, R.string.option_kokoro_voice_help);
                case "speed" -> new OptionPresentation(R.string.option_speed,
                        R.string.option_speed_help, true, false, true);
                default -> GENERIC;
            } : GENERIC;
        };
    }

    private static OptionPresentation multiline(int label, int help) {
        return new OptionPresentation(label, help, false, true, false);
    }

    int hint() {
        if (label == R.string.option_voice_description) return R.string.option_voice_description_hint;
        if (label == R.string.option_voice_style) return R.string.option_voice_style_hint;
        if (label == R.string.option_emotion_text) return R.string.option_emotion_text_hint;
        return 0;
    }

    private static OptionPresentation mossNano(String key) {
        return switch (key) {
            case "max_tokens" -> common(R.string.option_audio_frame_limit, R.string.option_moss_frame_limit_help);
            case "active_codebooks" -> advanced(R.string.option_codebooks, R.string.option_codebooks_help);
            case "do_sample" -> advanced(R.string.option_sampling, R.string.option_sampling_help);
            case "temperature" -> new OptionPresentation(R.string.option_audio_temperature,
                    R.string.option_temperature_help, false, false, true);
            case "text_temperature" -> new OptionPresentation(R.string.option_text_temperature,
                    R.string.option_temperature_help, false, false, true);
            case "top_p" -> advanced(R.string.option_audio_top_p, R.string.option_top_p_help);
            case "text_top_p" -> advanced(R.string.option_text_top_p, R.string.option_top_p_help);
            case "top_k" -> advanced(R.string.option_audio_top_k, R.string.option_top_k_help);
            case "text_top_k" -> advanced(R.string.option_text_top_k, R.string.option_top_k_help);
            case "repetition_penalty" -> new OptionPresentation(R.string.option_repetition_penalty,
                    R.string.option_repetition_penalty_help, false, false, true);
            case "seed" -> advanced(R.string.option_seed, R.string.option_seed_help);
            case "text_chunk_size" -> advanced(R.string.option_text_chunk_size, R.string.option_text_chunk_size_help);
            case "text_chunk_mode" -> advanced(R.string.option_text_chunk_mode, R.string.option_text_chunk_mode_help);
            default -> GENERIC;
        };
    }
}
