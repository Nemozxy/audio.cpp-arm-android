package com.audiocpp.ttsdemo;

import java.util.Arrays;

/** Offline workflows that need more than the generic option renderer. */
enum DemoModelProfile {
    GENERIC, INDEX, BREEZE, QWEN_BASE, QWEN_CUSTOM, QWEN_DESIGN, MOSS_TTS, MOSS_ASR, QWEN_ASR;

    enum Placement { DEFAULT, HIDDEN, SYNTHESIS, REFERENCE, TRANSCRIPTION }

    static DemoModelProfile forModel(String family, boolean speakerReference, boolean voiceDesign,
                                     boolean styleCondition) {
        return switch (family) {
            case "index_tts2" -> INDEX;
            case "breeze_tts" -> BREEZE;
            case "moss_tts_nano" -> MOSS_TTS;
            case "moss_transcribe_diarize" -> MOSS_ASR;
            case "qwen3_asr" -> QWEN_ASR;
            case "qwen3_tts" -> voiceDesign ? QWEN_DESIGN : speakerReference ? QWEN_BASE
                    : styleCondition ? QWEN_CUSTOM : GENERIC;
            default -> GENERIC;
        };
    }

    Placement placement(int scope, String key) {
        if (scope != ModelOption.REQUEST) {
            return this == QWEN_ASR && ("qwen3_asr.forced_aligner_path".equals(key)
                    || "qwen3_asr.vad_model_path".equals(key)) ? Placement.HIDDEN : Placement.DEFAULT;
        }
        if (this != GENERIC && key.startsWith("stream_")) return Placement.HIDDEN;
        if ("reference_text".equals(key)) {
            return switch (this) {
                case BREEZE, QWEN_BASE, MOSS_TTS, GENERIC -> Placement.REFERENCE;
                default -> Placement.HIDDEN;
            };
        }
        return switch (this) {
            case INDEX -> switch (key) {
                case "emotion_text", "emotion_alpha" -> Placement.SYNTHESIS;
                case "text", "use_emotion_text", "emotion_vector", "use_random_emotion" -> Placement.HIDDEN;
                default -> Placement.DEFAULT;
            };
            case BREEZE -> "instruct".equals(key) ? Placement.SYNTHESIS : Placement.DEFAULT;
            case QWEN_BASE -> switch (key) {
                case "x_vector_only_mode" -> Placement.REFERENCE;
                case "instruct", "voice_id" -> Placement.HIDDEN;
                default -> Placement.DEFAULT;
            };
            case QWEN_CUSTOM -> switch (key) {
                case "instruct", "voice_id" -> Placement.SYNTHESIS;
                case "x_vector_only_mode" -> Placement.HIDDEN;
                default -> Placement.DEFAULT;
            };
            case QWEN_DESIGN -> switch (key) {
                case "instruct" -> Placement.SYNTHESIS;
                case "voice_id", "x_vector_only_mode" -> Placement.HIDDEN;
                default -> Placement.DEFAULT;
            };
            case MOSS_ASR -> "instruct".equals(key) ? Placement.TRANSCRIPTION : Placement.DEFAULT;
            case QWEN_ASR -> switch (key) {
                case "language", "audio_chunk_mode" -> Placement.TRANSCRIPTION;
                case "return_timestamps", "clamp_timestamps_to_audio", "preserve_punctuation" -> Placement.HIDDEN;
                default -> Placement.DEFAULT;
            };
            default -> Placement.DEFAULT;
        };
    }

    boolean requiresSpeakerReference() {
        return this == QWEN_BASE || this == INDEX;
    }

    boolean requiresReferenceText(boolean hasReference, boolean embeddingOnly) {
        return hasReference && (this == BREEZE || (this == QWEN_BASE && !embeddingOnly));
    }

    boolean requiresPresetVoice() {
        return this == QWEN_CUSTOM;
    }

    boolean hasLanguagePicker(int scope, String key) {
        return scope == ModelOption.REQUEST && "language".equals(key)
                && (this == INDEX || this == QWEN_ASR || this == QWEN_BASE || this == QWEN_CUSTOM
                    || this == QWEN_DESIGN);
    }

    String[] choiceValues(int scope, String key, String[] declared, String[] languages) {
        if (hasLanguagePicker(scope, key)) {
            String[] values = this == INDEX ? declared : languages;
            return Arrays.stream(values)
                    .filter(value -> !value.trim().isEmpty() && !value.equalsIgnoreCase("auto") && !value.equals("..."))
                    .distinct().toArray(String[]::new);
        }
        if (scope == ModelOption.REQUEST && this == QWEN_ASR && "audio_chunk_mode".equals(key)) {
            return Arrays.stream(declared).filter(value -> "fixed".equals(value) || "none".equals(value))
                    .toArray(String[]::new);
        }
        return declared;
    }
}
