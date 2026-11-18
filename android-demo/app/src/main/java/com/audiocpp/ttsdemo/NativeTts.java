package com.audiocpp.ttsdemo;

final class NativeTts {
    static {
        System.loadLibrary("c++_shared");
        System.loadLibrary("audiocpp");
        System.loadLibrary("audiocpp_jni");
    }

    private NativeTts() {
    }

    static native String nativeVersion();

    /** Enables a separate instrumented run; timing benchmarks should leave this off. */
    static native void setProfiling(boolean enabled);

    /** Returns native diagnostics for the most recent inference attempt. */
    static native String performanceReport();

    /** Returns model capabilities and declared options as JSON. */
    static native String inspectModel(String modelPath);

    /** Returns null on success, otherwise a human-readable error. */
    static native String synthesize(String modelPath, String text, String outputPath,
                                    String referenceAudioPath, int threads,
                                    int[] scopes, String[] keys, String[] values);

    /** Returns a JSON object containing text and language; throws on failure. */
    static native String transcribe(String modelPath, String inputAudioPath, int threads,
                                    int[] scopes, String[] keys, String[] values);
}
