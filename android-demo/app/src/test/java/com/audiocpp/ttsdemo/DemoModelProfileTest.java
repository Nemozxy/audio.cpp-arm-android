package com.audiocpp.ttsdemo;

import org.junit.Test;

import static com.audiocpp.ttsdemo.DemoModelProfile.*;
import static com.audiocpp.ttsdemo.DemoModelProfile.Placement.*;
import static com.audiocpp.ttsdemo.ModelOption.REQUEST;
import static com.audiocpp.ttsdemo.ModelOption.SESSION;
import static org.junit.Assert.*;

public class DemoModelProfileTest {
    @Test
    public void qwenTtsVariantsUseDifferentVoiceInputs() {
        assertEquals(QWEN_BASE, forModel("qwen3_tts", true, false, false));
        assertEquals(QWEN_CUSTOM, forModel("qwen3_tts", false, false, true));
        assertEquals(QWEN_DESIGN, forModel("qwen3_tts", false, true, true));
        assertEquals(GENERIC, forModel("another_family", false, true, true));
        assertEquals(REFERENCE, QWEN_BASE.placement(REQUEST, "reference_text"));
        assertEquals(REFERENCE, QWEN_BASE.placement(REQUEST, "x_vector_only_mode"));
        assertEquals(HIDDEN, QWEN_BASE.placement(REQUEST, "instruct"));
        assertEquals(SYNTHESIS, QWEN_CUSTOM.placement(REQUEST, "voice_id"));
        assertEquals(SYNTHESIS, QWEN_CUSTOM.placement(REQUEST, "instruct"));
        assertEquals(HIDDEN, QWEN_CUSTOM.placement(REQUEST, "reference_text"));
        assertEquals(SYNTHESIS, QWEN_DESIGN.placement(REQUEST, "instruct"));
        assertEquals(HIDDEN, QWEN_DESIGN.placement(REQUEST, "voice_id"));
        assertEquals(DEFAULT, QWEN_DESIGN.placement(SESSION, "instruct"));
        assertTrue(QWEN_CUSTOM.requiresPresetVoice());
        assertFalse(QWEN_DESIGN.requiresPresetVoice());
    }

    @Test
    public void referenceRequirementsMatchTheCloneWorkflow() {
        assertTrue(QWEN_BASE.requiresSpeakerReference());
        assertTrue(INDEX.requiresSpeakerReference());
        assertFalse(BREEZE.requiresSpeakerReference());
        assertFalse(MOSS_TTS.requiresSpeakerReference());
        assertTrue(QWEN_BASE.requiresReferenceText(true, false));
        assertFalse(QWEN_BASE.requiresReferenceText(true, true));
        assertTrue(BREEZE.requiresReferenceText(true, false));
        assertFalse(BREEZE.requiresReferenceText(false, false));
        assertFalse(MOSS_TTS.requiresReferenceText(true, false));
        assertEquals(REFERENCE, MOSS_TTS.placement(REQUEST, "reference_text"));
        assertEquals(SYNTHESIS, BREEZE.placement(REQUEST, "instruct"));
    }

    @Test
    public void indexPromotesEmotionButHidesItsInternalSwitch() {
        assertEquals(INDEX, forModel("index_tts2", true, false, true));
        assertEquals(SYNTHESIS, INDEX.placement(REQUEST, "emotion_text"));
        assertEquals(SYNTHESIS, INDEX.placement(REQUEST, "emotion_alpha"));
        assertEquals(HIDDEN, INDEX.placement(REQUEST, "use_emotion_text"));
        assertEquals(HIDDEN, INDEX.placement(REQUEST, "text"));
    }

    @Test
    public void asrProfilesOnlyOfferAvailableOfflineControls() {
        assertEquals(TRANSCRIPTION, MOSS_ASR.placement(REQUEST, "instruct"));
        assertEquals(TRANSCRIPTION, QWEN_ASR.placement(REQUEST, "language"));
        assertEquals(TRANSCRIPTION, QWEN_ASR.placement(REQUEST, "audio_chunk_mode"));
        assertEquals(HIDDEN, QWEN_ASR.placement(REQUEST, "return_timestamps"));
        assertEquals(HIDDEN, QWEN_ASR.placement(SESSION, "qwen3_asr.forced_aligner_path"));
        assertEquals(HIDDEN, BREEZE.placement(REQUEST, "stream_chunk_policy"));
        assertEquals(DEFAULT, GENERIC.placement(REQUEST, "return_timestamps"));
        assertArrayEquals(new String[]{"fixed", "none"}, QWEN_ASR.choiceValues(REQUEST, "audio_chunk_mode",
                new String[]{"auto", "fixed", "vad", "none"}, new String[0]));
    }

    @Test
    public void languagePickersKeepNativeValuesAndUseBlankForAuto() {
        assertArrayEquals(new String[]{"Chinese", "English", "Cantonese"}, QWEN_ASR.choiceValues(REQUEST,
                "language", new String[0], new String[]{"Auto", "Chinese", "English", "Cantonese", "Chinese"}));
        assertArrayEquals(new String[]{"zh", "ja"}, INDEX.choiceValues(REQUEST,
                "language", new String[]{"auto", "zh", "ja", "..."}, new String[]{"Chinese"}));
    }
}
