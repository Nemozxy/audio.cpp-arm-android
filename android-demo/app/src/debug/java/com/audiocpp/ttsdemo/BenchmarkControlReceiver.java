package com.audiocpp.ttsdemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Debug-only A/B driver. The production activity remains the interactive UI;
 * this receiver invokes the same JNI entry points with fixed model/input/thread
 * values and writes a complete report under the app-private files directory.
 */
public final class BenchmarkControlReceiver extends BroadcastReceiver {
    private static final String ACTION = "com.audiocpp.ttsdemo.BENCHMARK_CONTROL";
    private static final String ASR_MODEL = "models/moss-transcribe-diarize-q4_k.gguf";
    private static final String TTS_MODEL = "models/moss-tts-nano-100m-q8_0.gguf";
    private static final String ASR_INPUT = "asr-input.wav";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        String command = intent.getStringExtra("command");
        if ("cleanup".equals(command)) {
            deleteRecursively(new File(context.getFilesDir(), "benchmark-results"));
            return;
        }
        if (!"run".equals(command)) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final String name = safeName(intent.getStringExtra("name"), "run");
        final String task = intent.getStringExtra("task");
        final String backend = intent.getStringExtra("backend");
        final boolean profiling = intent.getBooleanExtra("profiling", false);
        final int threads = intent.getIntExtra("threads", 4);
        new Thread(() -> runBenchmark(appContext, name, task, backend, profiling, threads),
                "audio-cpp-benchmark").start();
    }

    private void runBenchmark(Context context, String name, String task, String backend,
                              boolean profiling, int threads) {
        File resultDir = new File(context.getFilesDir(), "benchmark-results");
        File reportFile = new File(resultDir, name + ".txt");
        File doneFile = new File(resultDir, name + ".done");
        StringBuilder report = new StringBuilder(1 << 20);
        String status = "failed";
        String message = "";
        String resultJson = "";
        File outputWav = null;
        try {
            resultDir.mkdirs();
            doneFile.delete();
            reportFile.delete();

            StringBuilder header = new StringBuilder();
            header.append("audio.cpp Android controlled benchmark\n");
            header.append("command.run=").append(name).append('\n');
            header.append("command.task=").append(value(task)).append('\n');
            header.append("command.backend=").append(value(backend)).append('\n');
            header.append("command.threads=").append(threads).append('\n');
            header.append("command.profiling=").append(profiling).append('\n');
            header.append("command.started_at=").append(timestamp()).append("\n\n");
            report.append(header);

            NativeTts.setProfiling(profiling);
            if ("tts".equals(task)) {
                File model = requireFile(new File(context.getFilesDir(), TTS_MODEL), "TTS model");
                outputWav = new File(context.getFilesDir(), name + "-tts-output.wav");
                String error = NativeTts.synthesize(model.getAbsolutePath(),
                        context.getString(R.string.default_text), outputWav.getAbsolutePath(),
                        null, threads, backend, new int[0], new String[0], new String[0]);
                if (error != null) {
                    message = error;
                } else {
                    status = "ok";
                }
            } else if ("asr".equals(task)) {
                File model = requireFile(new File(context.getFilesDir(), ASR_MODEL), "ASR model");
                File input = requireFile(new File(context.getFilesDir(), ASR_INPUT), "ASR input");
                try {
                    resultJson = NativeTts.transcribe(model.getAbsolutePath(),
                            input.getAbsolutePath(), threads, backend,
                            new int[0], new String[0], new String[0]);
                    status = "ok";
                } catch (Throwable failure) {
                    message = String.valueOf(failure.getMessage() == null ? failure : failure.getMessage());
                }
            } else {
                message = "Unsupported task: " + task;
            }

            report.append("result.status=").append(status).append('\n');
            report.append("result.message=").append(value(message)).append('\n');
            if (!resultJson.isEmpty()) {
                report.append("result.json=").append(resultJson).append('\n');
            }
            if (outputWav != null) {
                report.append("result.output_wav=").append(outputWav.getName()).append('\n');
                report.append("result.output_wav_bytes=").append(
                        outputWav.isFile() ? outputWav.length() : -1).append('\n');
            }
            report.append("result.finished_at=").append(timestamp()).append("\n\n");
            report.append("[performance report]\n");
            try {
                String performance = NativeTts.performanceReport();
                report.append(performance == null ? "" : performance);
            } catch (Throwable failure) {
                report.append("Unable to read performance report: ").append(failure);
            }
            write(reportFile, report.toString());
            write(doneFile, "");
        } catch (Throwable failure) {
            try {
                resultDir.mkdirs();
                report.append("result.status=failed\n");
                report.append("result.message=").append(value(String.valueOf(failure))).append('\n');
                write(reportFile, report.toString());
                write(doneFile, "");
            } catch (Throwable ignored) {
                // The caller observes the missing .done marker as the failure signal.
            }
        }
    }

    private static File requireFile(File file, String label) {
        if (!file.isFile()) {
            throw new IllegalStateException(label + " is missing: " + file.getAbsolutePath());
        }
        return file;
    }

    private static void write(File file, String contents) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(contents.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private static String safeName(String value, String fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
    }

    private static String value(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
