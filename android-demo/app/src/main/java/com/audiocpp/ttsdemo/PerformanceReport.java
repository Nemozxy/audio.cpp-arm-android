package com.audiocpp.ttsdemo;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class PerformanceReport {
    private PerformanceReport() {
    }

    static String build(Context context, String nativeVersion, String modelFamily, String modelName,
                        String task, String threads, String status, String transcript) {
        String appVersion = "";
        try {
            appVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception error) {
            appVersion = "unknown";
        }
        StringBuilder report = new StringBuilder(16384);
        report.append("audio.cpp Android performance report\n");
        report.append("schema=1\n");
        report.append("created_at=").append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                .format(new Date())).append('\n');
        report.append("app.version=").append(value(appVersion)).append('\n');
        report.append("device.manufacturer=").append(value(Build.MANUFACTURER)).append('\n');
        report.append("device.model=").append(value(Build.MODEL)).append('\n');
        report.append("device.product=").append(value(Build.PRODUCT)).append('\n');
        report.append("device.hardware=").append(value(Build.HARDWARE)).append('\n');
        report.append("device.board=").append(value(Build.BOARD)).append('\n');
        if (Build.VERSION.SDK_INT >= 31) {
            report.append("device.soc_model=").append(value(Build.SOC_MODEL)).append('\n');
            report.append("device.soc_manufacturer=").append(value(Build.SOC_MANUFACTURER)).append('\n');
        }
        report.append("device.android_release=").append(value(Build.VERSION.RELEASE)).append('\n');
        report.append("device.sdk_int=").append(Build.VERSION.SDK_INT).append('\n');
        report.append("device.supported_abis=").append(value(String.join(",", Build.SUPPORTED_ABIS))).append('\n');
        report.append("device.online_processors=").append(Runtime.getRuntime().availableProcessors()).append('\n');
        report.append("device.max_heap_mb=").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append('\n');
        report.append("native.version=").append(value(nativeVersion)).append('\n');
        report.append("native.apk_abi=arm64-v8a\n");
        report.append("model.family=").append(value(modelFamily)).append('\n');
        report.append("model.file=").append(value(modelName)).append('\n');
        report.append("run.task=").append(value(task)).append('\n');
        report.append("run.threads=").append(value(threads)).append('\n');
        report.append("run.status=").append(value(status)).append("\n\n");
        report.append("[transcript]\n").append(transcript == null ? "" : transcript).append("\n\n");
        report.append("[native timing and diagnostics]\n");
        String nativeReport;
        try {
            nativeReport = NativeTts.performanceReport();
        } catch (Throwable error) {
            nativeReport = "Unable to read native report: " + error;
        }
        report.append(nativeReport == null || nativeReport.isEmpty()
                ? "No native inference report is available.\n" : nativeReport);
        return report.toString();
    }

    static String fileName(String task) {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return "audiocpp-" + value(task).replaceAll("[^A-Za-z0-9_-]", "_")
                + "-performance-" + timestamp + ".txt";
    }

    static void write(Context context, Uri destination, String contents) throws IOException {
        try (OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) {
                throw new IOException("无法打开所选文件");
            }
            output.write(contents.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static String value(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
