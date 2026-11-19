package com.audiocpp.ttsdemo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OptionPresentationTest {
    @Test
    public void qwenVoiceDesignPromotesOnlyItsRequestInstruction() {
        assertTrue(OptionPresentation.isQwenVoiceDescription("qwen3_tts", true, ModelOption.REQUEST, "instruct"));
        assertFalse(OptionPresentation.isQwenVoiceDescription("qwen3_tts", false, ModelOption.REQUEST, "instruct"));
        assertFalse(OptionPresentation.isQwenVoiceDescription("moss_tts_nano", true, ModelOption.REQUEST, "instruct"));
        assertFalse(OptionPresentation.isQwenVoiceDescription("qwen3_tts", true, ModelOption.SESSION, "instruct"));
        assertFalse(OptionPresentation.isQwenVoiceDescription("qwen3_tts", true, ModelOption.REQUEST, "language"));
    }
}
