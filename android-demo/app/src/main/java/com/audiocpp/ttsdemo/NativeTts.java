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

    /** Returns null on success, otherwise a human-readable error. */
    static native String synthesize(String modelPath, String text, String outputPath, int threads);
}
