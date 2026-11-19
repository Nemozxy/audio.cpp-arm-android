package com.audiocpp.ttsdemo;

/** Demo-specific wording and prominence. Controls and defaults still come from the model. */
record OptionPresentation(int label, int help, boolean common, boolean multiline, boolean positive) {
    private static final OptionPresentation GENERIC = new OptionPresentation(0, 0, false, false, false);
    static final OptionPresentation QWEN_VOICE_DESCRIPTION = new OptionPresentation(
            R.string.option_voice_description, 0, true, true, false);

    static boolean isQwenVoiceDescription(String family, boolean supportsVoiceDesign, int scope, String key) {
        return "qwen3_tts".equals(family) && supportsVoiceDesign
                && scope == ModelOption.REQUEST && "instruct".equals(key);
    }

    private static OptionPresentation common(int label, int help) {
        return new OptionPresentation(label, help, true, false, false);
    }

    private static OptionPresentation advanced(int label, int help) {
        return new OptionPresentation(label, help, false, false, false);
    }

    static OptionPresentation forOption(String family, ModelOption option) {
        if (option.scope != ModelOption.REQUEST) return GENERIC;
        if ("reference_text".equals(option.name)) {
            return new OptionPresentation(R.string.option_reference_text,
                    "moss_tts_nano".equals(family) ? R.string.option_reference_text_help : 0,
                    false, true, false);
        }
        return switch (family) {
            case "moss_tts_nano" -> mossNano(option.name);
            case "moss_transcribe_diarize" -> switch (option.name) {
                case "max_tokens" -> common(R.string.option_transcript_limit,
                        R.string.option_transcript_limit_help);
                case "instruct" -> new OptionPresentation(R.string.option_transcription_prompt,
                        R.string.option_transcription_prompt_help, false, true, false);
                default -> GENERIC;
            };
            case "qwen3_asr" -> switch (option.name) {
                case "language" -> common(R.string.option_recognition_language,
                        R.string.option_recognition_language_help);
                case "max_tokens" -> common(R.string.option_transcript_limit,
                        R.string.option_transcript_limit_help);
                default -> GENERIC;
            };
            case "qwen3_tts" -> "language".equals(option.name)
                    ? common(R.string.option_synthesis_language, R.string.option_synthesis_language_help)
                    : GENERIC;
            case "kokoro_tts" -> switch (option.name) {
                case "voice_id" -> common(R.string.option_voice, R.string.option_kokoro_voice_help);
                case "speed" -> new OptionPresentation(R.string.option_speed,
                        R.string.option_speed_help, true, false, true);
                default -> GENERIC;
            };
            default -> GENERIC;
        };
    }

    private static OptionPresentation mossNano(String key) {
        return switch (key) {
            case "max_tokens" -> common(R.string.option_audio_frame_limit, R.string.option_audio_frame_limit_help);
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
