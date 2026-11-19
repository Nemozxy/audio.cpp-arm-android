package com.audiocpp.ttsdemo;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "AudioCppDemo";
    private static final String TTS_MODEL_ASSET = "models/moss-tts-nano-100m-q8_0.gguf";
    private static final String ASR_MODEL_ASSET = "models/moss-transcribe-diarize-q4_k.gguf";
    private static final String PREFS = "model-selection";
    private static final String PREF_MODEL_PATH = "model-path";
    private static final int REQUEST_REFERENCE_AUDIO = 42;
    private static final int REQUEST_ASR_AUDIO = 43;
    private static final int REQUEST_GGUF_MODEL = 44;
    private static final int REQUEST_EXPORT_PERFORMANCE = 45;

    private static final class TaskEntry {
        final String id;
        final String label;

        TaskEntry(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class RunMetadata {
        final String task;
        final String modelFamily;
        final String modelName;
        final String threads;
        final String backend;
        final boolean profiling;
        String status = "";
        String transcript = "";

        RunMetadata(String task, String modelFamily, String modelName, String threads,
                    String backend, boolean profiling) {
            this.task = task;
            this.modelFamily = modelFamily;
            this.modelName = modelName;
            this.threads = threads;
            this.backend = backend;
            this.profiling = profiling;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<TaskEntry> tasks = new ArrayList<>();

    private EditText inputText;
    private EditText threadsInput;
    private CheckBox profilingEnabled;
    private Button synthesizeButton;
    private Button playButton;
    private Button resetOptionsButton;
    private Button selectReferenceButton;
    private Button clearReferenceButton;
    private Button selectAsrAudioButton;
    private Button builtinTtsButton;
    private Button builtinAsrButton;
    private Button importModelButton;
    private Button exportPerformanceButton;
    private ProgressBar progress;
    private TextView modelName;
    private TextView modelStatus;
    private TextView referenceStatus;
    private TextView asrAudioStatus;
    private TextView statusText;
    private TextView transcriptTitle;
    private TextView transcriptText;
    private LinearLayout referenceSection;
    private LinearLayout ttsInputSection;
    private LinearLayout asrInputSection;
    private Spinner taskSpinner;
    private ParameterPanel parameterPanel;
    private File modelFile;
    private File ttsModelFile;
    private File asrModelFile;
    private File wavFile;
    private File referenceFile;
    private File asrAudioFile;
    private String referenceName;
    private String asrAudioName;
    private String nativeVersion = "";
    private String modelFamily = "";
    private String pendingPerformanceReport;
    private RunMetadata lastRun;
    private MediaPlayer player;
    private boolean modelReady;
    private boolean busy;
    private boolean hasPerformanceReport;
    private boolean supportsTts;
    private boolean supportsClon;
    private boolean speakerReferenceSupported;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputText = findViewById(R.id.inputText);
        threadsInput = findViewById(R.id.threadsInput);
        profilingEnabled = findViewById(R.id.profilingEnabled);
        synthesizeButton = findViewById(R.id.synthesizeButton);
        playButton = findViewById(R.id.playButton);
        resetOptionsButton = findViewById(R.id.resetOptionsButton);
        selectReferenceButton = findViewById(R.id.selectReferenceButton);
        clearReferenceButton = findViewById(R.id.clearReferenceButton);
        selectAsrAudioButton = findViewById(R.id.selectAsrAudioButton);
        builtinTtsButton = findViewById(R.id.builtinTtsButton);
        builtinAsrButton = findViewById(R.id.builtinAsrButton);
        importModelButton = findViewById(R.id.importModelButton);
        exportPerformanceButton = findViewById(R.id.exportPerformanceButton);
        progress = findViewById(R.id.progress);
        modelName = findViewById(R.id.modelName);
        modelStatus = findViewById(R.id.modelStatus);
        referenceStatus = findViewById(R.id.referenceStatus);
        asrAudioStatus = findViewById(R.id.asrAudioStatus);
        statusText = findViewById(R.id.statusText);
        transcriptTitle = findViewById(R.id.transcriptTitle);
        transcriptText = findViewById(R.id.transcriptText);
        referenceSection = findViewById(R.id.referenceSection);
        ttsInputSection = findViewById(R.id.ttsInputSection);
        asrInputSection = findViewById(R.id.asrInputSection);
        taskSpinner = findViewById(R.id.taskSpinner);
        parameterPanel = new ParameterPanel(this, findViewById(R.id.parametersContainer),
                findViewById(R.id.referenceParametersContainer));

        ttsModelFile = new File(getFilesDir(), TTS_MODEL_ASSET);
        asrModelFile = new File(getFilesDir(), ASR_MODEL_ASSET);
        wavFile = new File(getFilesDir(), "tts-output.wav");
        referenceFile = new File(getFilesDir(), "voice-reference.wav");
        asrAudioFile = new File(getFilesDir(), "asr-input.wav");
        referenceName = referenceFile.getName();
        asrAudioName = asrAudioFile.getName();

        synthesizeButton.setOnClickListener(view -> runSelectedTask());
        profilingEnabled.setOnCheckedChangeListener((view, enabled) -> NativeTts.setProfiling(enabled));
        playButton.setOnClickListener(view -> playAudio());
        exportPerformanceButton.setOnClickListener(view -> exportPerformanceReport());
        builtinTtsButton.setOnClickListener(view -> selectModel(ttsModelFile, TTS_MODEL_ASSET));
        builtinAsrButton.setOnClickListener(view -> selectModel(asrModelFile, ASR_MODEL_ASSET));
        importModelButton.setOnClickListener(view -> selectGgufModel());
        selectReferenceButton.setOnClickListener(view -> selectAudio(REQUEST_REFERENCE_AUDIO));
        selectAsrAudioButton.setOnClickListener(view -> selectAudio(REQUEST_ASR_AUDIO));
        clearReferenceButton.setOnClickListener(view -> clearReferenceAudio());
        resetOptionsButton.setOnClickListener(view -> {
            parameterPanel.reset();
            statusText.setText(R.string.parameters_reset);
        });
        taskSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateTaskUi();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        updateAudioStatuses();
        File initialModel = ttsModelFile;
        String initialAsset = TTS_MODEL_ASSET;
        String savedPath = getPreferences(MODE_PRIVATE).getString(PREF_MODEL_PATH, "");
        if (asrModelFile.getAbsolutePath().equals(savedPath)) {
            initialModel = asrModelFile;
            initialAsset = ASR_MODEL_ASSET;
        } else if (!ttsModelFile.getAbsolutePath().equals(savedPath)
                && savedPath != null && !savedPath.isEmpty()) {
            File custom = new File(savedPath);
            if (custom.isFile()) {
                initialModel = custom;
                initialAsset = null;
            }
        }
        selectModel(initialModel, initialAsset);
    }

    private void selectModel(File target, String asset) {
        if (busy) {
            return;
        }
        releasePlayer();
        modelFile = target;
        lastRun = null;
        hasPerformanceReport = false;
        modelReady = false;
        supportsTts = false;
        supportsClon = false;
        speakerReferenceSupported = false;
        tasks.clear();
        parameterPanel.clear();
        transcriptTitle.setVisibility(View.GONE);
        transcriptText.setVisibility(View.GONE);
        modelName.setText(target.getName());
        modelStatus.setText(R.string.detecting_model);
        statusText.setText(asset == null ? R.string.detecting_model : R.string.extracting_model);
        setBusy(true);

        worker.execute(() -> {
            try {
                nativeVersion = NativeTts.nativeVersion();
                if (asset != null) {
                    ensureModelExtracted(target, asset);
                }
                if (!target.isFile()) {
                    throw new IOException("模型文件不存在：" + target.getName());
                }
                postToUi(() -> modelStatus.setText(R.string.detecting_model));
                JSONObject inspection = new JSONObject(NativeTts.inspectModel(target.getAbsolutePath()));
                String family = inspection.getString("family");
                List<ModelOption> options = ModelOption.readOptions(inspection.getJSONArray("options"));
                postToUi(() -> {
                    if (modelFile == null || !modelFile.equals(target)) {
                        return;
                    }
                    modelName.setText(target.getName() + "\n" + family);
                    modelFamily = family;
                    supportsTts = inspection.optBoolean("supportsTts");
                    supportsClon = inspection.optBoolean("supportsClon");
                    speakerReferenceSupported = inspection.optBoolean("supportsSpeakerReference");
                    tasks.clear();
                    if (supportsTts || supportsClon) {
                        tasks.add(new TaskEntry("tts", getString(R.string.task_tts)));
                    }
                    if (inspection.optBoolean("supportsAsr")) {
                        tasks.add(new TaskEntry("asr", getString(R.string.task_asr)));
                    }
                    ArrayAdapter<TaskEntry> adapter = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_item, tasks);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    taskSpinner.setAdapter(adapter);
                    parameterPanel.setOptions(family, options);
                    modelReady = true;
                    setBusy(false);
                    updateTaskUi();
                    modelStatus.setText(family + " · audio.cpp " + nativeVersion + " · ARM64");
                    statusText.setText(R.string.model_ready);
                    SharedPreferences preferences = getPreferences(MODE_PRIVATE);
                    preferences.edit().putString(PREF_MODEL_PATH, target.getAbsolutePath()).apply();
                });
            } catch (Throwable error) {
                Log.e(TAG, "Model loading failed", error);
                postToUi(() -> showError("模型加载失败：" + readableMessage(error)));
            }
        });
    }

    private void selectGgufModel() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(Intent.createChooser(intent, "选择 GGUF 模型"), REQUEST_GGUF_MODEL);
    }

    private void selectAudio(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        String title = requestCode == REQUEST_REFERENCE_AUDIO ? "选择 WAV 参考音频" : "选择要识别的 WAV";
        startActivityForResult(Intent.createChooser(intent, title), requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_EXPORT_PERFORMANCE) {
            if (pendingPerformanceReport != null) {
                try {
                    PerformanceReport.write(this, data.getData(), pendingPerformanceReport);
                    statusText.setText(R.string.performance_exported);
                } catch (Exception error) {
                    Log.e(TAG, "Performance report export failed", error);
                    showError(getString(R.string.performance_export_failed, readableMessage(error)));
                } finally {
                    pendingPerformanceReport = null;
                }
            }
            return;
        }
        if (requestCode == REQUEST_GGUF_MODEL) {
            importGgufModel(data.getData());
        } else if (requestCode == REQUEST_REFERENCE_AUDIO || requestCode == REQUEST_ASR_AUDIO) {
            importAudio(data.getData(), requestCode == REQUEST_REFERENCE_AUDIO);
        }
    }

    private void exportPerformanceReport() {
        RunMetadata run = lastRun;
        if (busy || !hasPerformanceReport || run == null) {
            return;
        }
        pendingPerformanceReport = PerformanceReport.build(this, nativeVersion, run.modelFamily,
                run.modelName, run.task, run.threads, run.backend, run.profiling, run.status, run.transcript);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, PerformanceReport.fileName(run.task));
        try {
            startActivityForResult(intent, REQUEST_EXPORT_PERFORMANCE);
        } catch (Exception error) {
            pendingPerformanceReport = null;
            showError(getString(R.string.performance_export_failed, readableMessage(error)));
        }
    }

    private void importGgufModel(Uri source) {
        String displayName = queryDisplayName(source, "custom.gguf")
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (!displayName.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            displayName += ".gguf";
        }
        File directory = new File(getFilesDir(), "custom-models");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            showError("无法创建自定义模型目录");
            return;
        }
        File destination = new File(directory, System.currentTimeMillis() + "-" + displayName);
        setBusy(true);
        modelStatus.setText("正在复制自定义 GGUF…");
        statusText.setText("正在复制自定义 GGUF，大文件可能需要一些时间…");
        worker.execute(() -> {
            try {
                copyUri(source, destination, true);
                postToUi(() -> {
                    setBusy(false);
                    selectModel(destination, null);
                });
            } catch (Exception error) {
                destination.delete();
                Log.e(TAG, "GGUF import failed", error);
                postToUi(() -> showError("自定义模型导入失败：" + readableMessage(error)));
            }
        });
    }

    private void importAudio(Uri source, boolean reference) {
        File destination = reference ? referenceFile : asrAudioFile;
        String displayName = queryDisplayName(source, destination.getName());
        if (reference) {
            referenceName = displayName;
        } else {
            asrAudioName = displayName;
        }
        setBusy(true);
        statusText.setText("正在复制音频…");
        worker.execute(() -> {
            try {
                copyUri(source, destination, false);
                postToUi(() -> {
                    if (reference) parameterPanel.resetReference();
                    setBusy(false);
                    updateAudioStatuses();
                    statusText.setText(reference
                            ? getString(R.string.reference_ready)
                            : "识别音频已就绪，可以开始识别");
                });
            } catch (Exception error) {
                Log.e(TAG, "Audio import failed", error);
                postToUi(() -> showError("音频导入失败：" + readableMessage(error)));
            }
        });
    }

    private void copyUri(Uri source, File destination, boolean model) throws IOException {
        File temporary = new File(destination.getPath() + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("无法清理旧的临时文件");
        }
        try (InputStream input = getContentResolver().openInputStream(source);
             FileOutputStream output = new FileOutputStream(temporary)) {
            if (input == null) {
                throw new IOException("无法读取所选文件");
            }
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
            validateCopiedFile(temporary, model);
            if (destination.exists() && !destination.delete()) {
                throw new IOException("无法替换旧文件");
            }
            if (!temporary.renameTo(destination)) {
                throw new IOException("无法保存导入文件");
            }
        } finally {
            if (temporary.exists()) {
                temporary.delete();
            }
        }
    }

    private static void validateCopiedFile(File file, boolean model) throws IOException {
        int minimum = model ? 4 : 44;
        if (file.length() < minimum) {
            throw new IOException(model ? "GGUF 文件为空或不完整" : "WAV 文件为空或不完整");
        }
        byte[] header = new byte[12];
        int count;
        try (FileInputStream input = new FileInputStream(file)) {
            count = input.read(header);
        }
        if (model) {
            if (count < 4 || header[0] != 'G' || header[1] != 'G'
                    || header[2] != 'U' || header[3] != 'F') {
                throw new IOException("所选文件不是有效的 GGUF 模型");
            }
        } else if (count < 12 || header[0] != 'R' || header[1] != 'I'
                || header[2] != 'F' || header[3] != 'F'
                || header[8] != 'W' || header[9] != 'A'
                || header[10] != 'V' || header[11] != 'E') {
            throw new IOException("请选择 RIFF/WAVE 格式的 WAV 音频");
        }
    }

    private String queryDisplayName(Uri source, String fallback) {
        try (Cursor cursor = getContentResolver().query(source,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String value = cursor.getString(column);
                    if (value != null && !value.trim().isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not read filename", error);
        }
        return fallback;
    }

    private void clearReferenceAudio() {
        if (referenceFile.exists() && !referenceFile.delete()) {
            showError("无法移除参考音频");
            return;
        }
        referenceName = referenceFile.getName();
        parameterPanel.resetReference();
        updateAudioStatuses();
        updateControls();
        statusText.setText(R.string.reference_empty);
    }

    private void updateAudioStatuses() {
        boolean referenceSelected = referenceFile.isFile();
        referenceStatus.setText(referenceSelected
                ? getString(R.string.reference_selected, referenceName)
                : getString(R.string.reference_empty));
        clearReferenceButton.setVisibility(referenceSelected ? View.VISIBLE : View.GONE);
        asrAudioStatus.setText(asrAudioFile.isFile()
                ? getString(R.string.asr_audio_selected, asrAudioName)
                : getString(R.string.asr_audio_empty));
        updateControls();
    }

    private void ensureModelExtracted(File target, String asset) throws IOException {
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("无法创建模型目录");
        }
        long expectedSize;
        try (AssetFileDescriptor descriptor = getAssets().openFd(asset)) {
            expectedSize = descriptor.getLength();
        }
        if (target.isFile() && target.length() == expectedSize) {
            return;
        }
        File temporary = new File(target.getPath() + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("无法清理旧的模型临时文件");
        }
        try (InputStream input = getAssets().open(asset);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        if (temporary.length() != expectedSize) {
            temporary.delete();
            throw new IOException("模型解压大小不匹配");
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("无法替换旧模型文件");
        }
        if (!temporary.renameTo(target)) {
            throw new IOException("无法保存模型文件");
        }
    }

    private String currentTask() {
        int position = taskSpinner.getSelectedItemPosition();
        if (position >= 0 && position < tasks.size()) {
            return tasks.get(position).id;
        }
        return "tts";
    }

    private String currentBackend() {
        Spinner spinner = findViewById(R.id.backendSpinner);
        return spinner.getSelectedItemPosition() == 1 ? "vulkan" : "cpu";
    }

    private void updateTaskUi() {
        boolean tts = "tts".equals(currentTask());
        ttsInputSection.setVisibility(tts ? View.VISIBLE : View.GONE);
        asrInputSection.setVisibility(!tts && modelReady ? View.VISIBLE : View.GONE);
        referenceSection.setVisibility(tts && modelReady && speakerReferenceSupported
                ? View.VISIBLE : View.GONE);
        playButton.setVisibility(tts ? View.VISIBLE : View.GONE);
        transcriptTitle.setVisibility(!tts && transcriptText.length() > 0 ? View.VISIBLE : View.GONE);
        transcriptText.setVisibility(!tts && transcriptText.length() > 0 ? View.VISIBLE : View.GONE);
        updateControls();
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        updateControls();
    }

    private void updateControls() {
        if (synthesizeButton == null) {
            return;
        }
        boolean tts = "tts".equals(currentTask());
        synthesizeButton.setText(tts ? R.string.synthesize : R.string.transcribe);
        synthesizeButton.setEnabled(modelReady && !busy);
        resetOptionsButton.setEnabled(modelReady && !busy);
        parameterPanel.setEnabled(modelReady && !busy);
        parameterPanel.setReferenceAvailable(modelReady && tts && speakerReferenceSupported && referenceFile.isFile());
        threadsInput.setEnabled(modelReady && !busy);
        profilingEnabled.setEnabled(!busy);
        findViewById(R.id.backendSpinner).setEnabled(!busy);
        taskSpinner.setEnabled(modelReady && !busy && tasks.size() > 1);
        selectReferenceButton.setEnabled(modelReady && !busy && tts && speakerReferenceSupported);
        clearReferenceButton.setEnabled(modelReady && !busy && tts && referenceFile.isFile());
        selectAsrAudioButton.setEnabled(modelReady && !busy && !tts);
        playButton.setEnabled(modelReady && !busy && tts && wavFile.isFile());
        builtinTtsButton.setEnabled(!busy);
        builtinAsrButton.setEnabled(!busy);
        importModelButton.setEnabled(!busy);
        exportPerformanceButton.setEnabled(hasPerformanceReport && !busy);
    }

    private void runSelectedTask() {
        if (!modelReady || busy) {
            showError("模型尚未就绪");
            return;
        }
        modelStatus.setText(modelFamily + " · audio.cpp " + nativeVersion + " · ARM64");
        if ("asr".equals(currentTask())) {
            transcribe();
        } else {
            synthesize();
        }
    }

    private ParameterPanel.Values collectOptions() {
        return parameterPanel.collect();
    }

    private int readThreads() {
        try {
            int threads = Integer.parseInt(threadsInput.getText().toString().trim());
            if (threads < 1 || threads > 32) {
                throw new NumberFormatException();
            }
            return threads;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("推理线程数需为 1～32 的整数");
        }
    }

    private void synthesize() {
        String text = inputText.getText().toString().trim();
        if (text.isEmpty()) {
            showError("请先输入要合成的文字");
            return;
        }
        if (!supportsTts && supportsClon && !referenceFile.isFile()) {
            showError("当前模型需要参考音频才能进行语音克隆");
            return;
        }
        ParameterPanel.Values options;
        int threads;
        try {
            options = collectOptions();
            threads = readThreads();
        } catch (IllegalArgumentException error) {
            showError(error.getMessage());
            return;
        }
        String selectedModel = modelFile.getAbsolutePath();
        String referencePath = speakerReferenceSupported && referenceFile.isFile()
                ? referenceFile.getAbsolutePath() : null;
        RunMetadata run = new RunMetadata("tts", modelFamily, new File(selectedModel).getName(),
                Integer.toString(threads), currentBackend(), profilingEnabled.isChecked());
        lastRun = run;
        hasPerformanceReport = true;
        setBusy(true);
        playButton.setEnabled(false);
        statusText.setText(R.string.working);
        long startedAt = System.nanoTime();
        worker.execute(() -> {
            String error;
            try {
                error = NativeTts.synthesize(selectedModel, text, wavFile.getAbsolutePath(),
                        referencePath, threads, run.backend, options.scopes, options.keys, options.values);
            } catch (Throwable failure) {
                Log.e(TAG, "Synthesis failed", failure);
                error = readableMessage(failure);
            }
            double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
            String resultError = error;
            postToUi(() -> {
                setBusy(false);
                if (resultError == null) {
                    run.status = String.format(Locale.getDefault(),
                            "合成完成，本次任务用时 %.1f 秒（不含模型加载），正在播放…", elapsedSeconds);
                    statusText.setText(run.status);
                    playButton.setEnabled(true);
                    playAudio();
                } else {
                    run.status = "合成失败：" + resultError;
                    showError(run.status);
                }
            });
        });
    }

    private void transcribe() {
        if (!asrAudioFile.isFile()) {
            showError("请先选择要识别的 WAV 音频");
            return;
        }
        ParameterPanel.Values options;
        int threads;
        try {
            options = collectOptions();
            threads = readThreads();
        } catch (IllegalArgumentException error) {
            showError(error.getMessage());
            return;
        }
        String selectedModel = modelFile.getAbsolutePath();
        String inputPath = asrAudioFile.getAbsolutePath();
        RunMetadata run = new RunMetadata("asr", modelFamily, new File(selectedModel).getName(),
                Integer.toString(threads), currentBackend(), profilingEnabled.isChecked());
        lastRun = run;
        hasPerformanceReport = true;
        setBusy(true);
        transcriptTitle.setVisibility(View.GONE);
        transcriptText.setVisibility(View.GONE);
        statusText.setText(R.string.transcribing);
        long startedAt = System.nanoTime();
        worker.execute(() -> {
            String transcript = null;
            String language = "";
            String error = null;
            try {
                JSONObject result = new JSONObject(NativeTts.transcribe(selectedModel, inputPath, threads,
                        run.backend, options.scopes, options.keys, options.values));
                transcript = result.optString("text");
                language = result.optString("language");
            } catch (Throwable failure) {
                Log.e(TAG, "Transcription failed", failure);
                error = readableMessage(failure);
            }
            double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
            String resultText = transcript;
            String resultLanguage = language;
            String resultError = error;
            postToUi(() -> {
                setBusy(false);
                if (resultError == null) {
                    run.transcript = resultText;
                    transcriptText.setText(resultText);
                    transcriptTitle.setVisibility(View.VISIBLE);
                    transcriptText.setVisibility(View.VISIBLE);
                    String languageSuffix = resultLanguage.isEmpty() ? "" : " · 语言 " + resultLanguage;
                    run.status = String.format(Locale.getDefault(),
                            "识别完成，本次任务用时 %.1f 秒（不含模型加载）%s", elapsedSeconds, languageSuffix);
                    statusText.setText(run.status);
                    transcriptText.announceForAccessibility(resultText);
                } else {
                    run.status = "识别失败：" + resultError;
                    showError(run.status);
                }
            });
        });
    }

    private void playAudio() {
        if (!wavFile.isFile()) {
            showError("还没有可播放的音频");
            return;
        }
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            player.setDataSource(wavFile.getAbsolutePath());
            player.setOnCompletionListener(mediaPlayer -> {
                playButton.setText(R.string.play);
                statusText.setText("播放完成，可修改文字或参数后再次合成");
            });
            player.setOnErrorListener((mediaPlayer, what, extra) -> {
                showError("播放失败：MediaPlayer " + what + "/" + extra);
                return true;
            });
            player.prepare();
            player.start();
            playButton.setText("播放中…");
        } catch (Exception error) {
            Log.e(TAG, "Playback failed", error);
            showError("播放失败：" + readableMessage(error));
        }
    }

    private void showError(String message) {
        busy = false;
        progress.setVisibility(View.GONE);
        updateControls();
        modelStatus.setText(message);
        statusText.setText(message);
        statusText.announceForAccessibility(message);
    }

    private void postToUi(Runnable action) {
        runOnUiThread(() -> {
            if (!isDestroyed()) {
                action.run();
            }
        });
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
        playButton.setText(R.string.play);
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        worker.shutdownNow();
        super.onDestroy();
    }
}
