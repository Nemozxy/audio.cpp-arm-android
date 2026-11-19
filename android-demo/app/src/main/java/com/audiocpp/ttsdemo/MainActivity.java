package com.audiocpp.ttsdemo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final String TAG = "AudioCppDemo";
    private static final int REQUEST_REFERENCE_AUDIO = 42;
    private static final int REQUEST_ASR_AUDIO = 43;
    private static final int REQUEST_GGUF_MODEL = 44;
    private static final int REQUEST_EXPORT_PERFORMANCE = 45;
    private static final int REQUEST_DOWNLOAD_NOTIFICATION = 46;
    private static final String DOWNLOAD_NOTIFICATION_ASKED = "download-notification-asked";
    private static final ModelCatalog MODEL_CATALOG = new ModelCatalog(
            ModelRepository::fetchCatalog, SystemClock::elapsedRealtime);
    private static boolean recoveredModelTransfers;

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
    private final ExecutorService catalogWorker = Executors.newFixedThreadPool(ModelRepository.Source.values().length);
    private final List<TaskEntry> tasks = new ArrayList<>();
    private final Runnable downloadObserver = this::renderDownload;

    private EditText inputText;
    private EditText threadsInput;
    private CheckBox profilingEnabled;
    private Button synthesizeButton;
    private Button playButton;
    private Button resetOptionsButton;
    private Button selectReferenceButton;
    private Button clearReferenceButton;
    private Button selectAsrAudioButton;
    private Button modelLibraryButton;
    private Button downloadModelButton;
    private Button importModelButton;
    private Button cancelModelTransfer;
    private Button exportPerformanceButton;
    private ProgressBar progress;
    private ProgressBar modelProgress;
    private TextView modelName;
    private TextView modelStatus;
    private TextView referenceTitle;
    private TextView referenceStatus;
    private TextView asrAudioHelp;
    private TextView asrAudioStatus;
    private TextView statusText;
    private TextView transcriptTitle;
    private TextView transcriptText;
    private LinearLayout referenceSection;
    private LinearLayout ttsInputSection;
    private LinearLayout asrInputSection;
    private Spinner taskSpinner;
    private ParameterPanel parameterPanel;
    private View inferenceControls;
    private ModelLibrary modelLibrary;
    private AtomicBoolean modelTransferCancelled;
    private File modelFile;
    private File restoreAfterDownload;
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
    private boolean downloading;
    private boolean hasPerformanceReport;
    private boolean requiresSpeakerReference;
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
        modelLibraryButton = findViewById(R.id.modelLibraryButton);
        downloadModelButton = findViewById(R.id.downloadModelButton);
        importModelButton = findViewById(R.id.importModelButton);
        cancelModelTransfer = findViewById(R.id.cancelModelTransfer);
        exportPerformanceButton = findViewById(R.id.exportPerformanceButton);
        progress = findViewById(R.id.progress);
        modelProgress = findViewById(R.id.modelProgress);
        inferenceControls = findViewById(R.id.inferenceControls);
        modelName = findViewById(R.id.modelName);
        modelStatus = findViewById(R.id.modelStatus);
        referenceTitle = findViewById(R.id.referenceTitle);
        referenceStatus = findViewById(R.id.referenceStatus);
        asrAudioHelp = findViewById(R.id.asrAudioHelp);
        asrAudioStatus = findViewById(R.id.asrAudioStatus);
        statusText = findViewById(R.id.statusText);
        transcriptTitle = findViewById(R.id.transcriptTitle);
        transcriptText = findViewById(R.id.transcriptText);
        referenceSection = findViewById(R.id.referenceSection);
        ttsInputSection = findViewById(R.id.ttsInputSection);
        asrInputSection = findViewById(R.id.asrInputSection);
        taskSpinner = findViewById(R.id.taskSpinner);
        parameterPanel = new ParameterPanel(this, findViewById(R.id.parametersContainer),
                findViewById(R.id.referenceParametersContainer), findViewById(R.id.synthesisParametersContainer),
                findViewById(R.id.transcriptionParametersContainer));

        modelLibrary = new ModelLibrary(getFilesDir());
        // Do not touch active transfers if only the Activity is recreated.
        if (!recoveredModelTransfers) {
            recoveredModelTransfers = true;
            modelLibrary.discardInterruptedTransfers();
        }
        wavFile = new File(getFilesDir(), "tts-output.wav");
        referenceFile = new File(getFilesDir(), "voice-reference.wav");
        asrAudioFile = new File(getFilesDir(), "asr-input.wav");
        referenceName = referenceFile.getName();
        asrAudioName = asrAudioFile.getName();

        synthesizeButton.setOnClickListener(view -> runSelectedTask());
        profilingEnabled.setOnCheckedChangeListener((view, enabled) -> NativeTts.setProfiling(enabled));
        playButton.setOnClickListener(view -> playAudio());
        exportPerformanceButton.setOnClickListener(view -> exportPerformanceReport());
        modelLibraryButton.setOnClickListener(view -> showModelLibrary());
        downloadModelButton.setOnClickListener(view ->
                new ModelCatalogDialog(this, modelPreferences(), catalogWorker, MODEL_CATALOG,
                        this::confirmDownload).show());
        importModelButton.setOnClickListener(view -> selectGgufModel());
        cancelModelTransfer.setOnClickListener(view -> {
            if (downloading) {
                ModelDownloadService.requestCancel(this);
                return;
            }
            if (modelTransferCancelled != null) modelTransferCancelled.set(true);
            cancelModelTransfer.setEnabled(false);
            modelStatus.setText(R.string.model_cancelling);
        });
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
        File initialModel = modelLibrary.selected(modelPreferences().getString(ModelLibrary.SELECTED_PATH, null));
        if (ModelDownloadService.current() == null) {
            if (initialModel != null) selectModel(initialModel);
        } else {
            restoreAfterDownload = initialModel;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ModelDownloadService.observe(downloadObserver);
    }

    @Override
    protected void onPause() {
        ModelDownloadService.removeObserver(downloadObserver);
        super.onPause();
    }

    private SharedPreferences modelPreferences() {
        return getSharedPreferences(ModelLibrary.PREFERENCES, MODE_PRIVATE);
    }

    private void selectModel(File target) {
        selectModel(target, null);
    }

    private void selectModel(File target, String downloadMessage) {
        if (busy) return;
        busy = true;
        updateControls();
        modelStatus.setText(R.string.detecting_model);
        modelProgress.setIndeterminate(true);
        modelProgress.setVisibility(View.VISIBLE);

        worker.execute(() -> {
            try {
                String version = NativeTts.nativeVersion();
                JSONObject inspection = new JSONObject(NativeTts.inspectModel(target.getAbsolutePath()));
                String family = inspection.getString("family");
                List<ModelOption> options = ModelOption.readOptions(inspection.getJSONArray("options"));
                JSONArray languageArray = inspection.getJSONArray("languages");
                String[] languages = new String[languageArray.length()];
                for (int i = 0; i < languages.length; i++) languages[i] = languageArray.getString(i);
                boolean supportsTts = inspection.optBoolean("supportsTts");
                boolean supportsClon = inspection.optBoolean("supportsClon");
                boolean supportsReference = inspection.optBoolean("supportsSpeakerReference");
                DemoModelProfile profile = DemoModelProfile.forModel(family, supportsReference,
                        inspection.optBoolean("supportsVoiceDesign"), inspection.optBoolean("supportsStyleCondition"));
                boolean tts = supportsTts || supportsClon;
                boolean asr = inspection.optBoolean("supportsAsr");
                if (!tts && !asr) throw new IOException(getString(R.string.model_no_task));
                postToUi(() -> {
                    releasePlayer();
                    modelFile = target;
                    lastRun = null;
                    hasPerformanceReport = false;
                    transcriptText.setText("");
                    modelName.setText(target.getName() + "\n" + family);
                    nativeVersion = version;
                    modelFamily = family;
                    requiresSpeakerReference = profile.requiresSpeakerReference() || (!supportsTts && supportsClon);
                    speakerReferenceSupported = supportsReference;
                    referenceTitle.setText(requiresSpeakerReference ? R.string.reference_title_required
                            : R.string.reference_title);
                    asrAudioHelp.setText(switch (profile) {
                        case MOSS_ASR -> R.string.asr_input_help_moss;
                        case QWEN_ASR -> R.string.asr_input_help_qwen;
                        default -> R.string.asr_input_help;
                    });
                    tasks.clear();
                    if (tts) {
                        tasks.add(new TaskEntry("tts", getString(R.string.task_tts)));
                    }
                    if (asr) {
                        tasks.add(new TaskEntry("asr", getString(R.string.task_asr)));
                    }
                    ArrayAdapter<TaskEntry> adapter = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_item, tasks);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    taskSpinner.setAdapter(adapter);
                    parameterPanel.setOptions(family, profile, languages, options);
                    modelReady = true;
                    modelProgress.setVisibility(View.GONE);
                    setBusy(false);
                    updateAudioStatuses();
                    updateTaskUi();
                    modelStatus.setText(downloadMessage == null ? readyModelStatus()
                            : readyModelStatus() + "\n" + downloadMessage);
                    statusText.setText(R.string.model_ready);
                    modelPreferences().edit().putString(ModelLibrary.SELECTED_PATH, target.getAbsolutePath()).apply();
                });
            } catch (Throwable error) {
                Log.e(TAG, "Model loading failed", error);
                postToUi(() -> finishModelTransfer(null, getString(R.string.model_load_failed,
                        target.getName(), readableMessage(error))));
            }
        });
    }

    private String readyModelStatus() {
        return modelFamily + " · audio.cpp " + nativeVersion + " · ARM64";
    }

    private void showModelLibrary() {
        List<File> models = modelLibrary.files();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.model_library).setNegativeButton(android.R.string.cancel, null);
        if (models.isEmpty()) {
            dialog.setMessage(R.string.model_library_empty).show();
            return;
        }
        String[] labels = new String[models.size()];
        for (int i = 0; i < labels.length; i++) {
            File file = models.get(i);
            int origin = switch (modelLibrary.area(file)) {
                case "downloaded-models" -> R.string.model_origin_downloaded;
                case "custom-models" -> R.string.model_origin_imported;
                default -> R.string.model_origin_legacy;
            };
            labels[i] = file.getName() + "\n" + Formatter.formatFileSize(this, file.length())
                    + " · " + getString(origin)
                    + (file.equals(modelFile) ? getString(R.string.model_active) : "");
        }
        dialog.setItems(labels, (ignored, index) -> {
            File file = models.get(index);
            AlertDialog.Builder actions = new AlertDialog.Builder(this).setTitle(file.getName())
                    .setNegativeButton(android.R.string.cancel, null);
            if (file.equals(modelFile)) {
                actions.setMessage(R.string.model_active_detail);
            } else {
                actions.setPositiveButton(R.string.model_use, (d, w) -> selectModel(file));
            }
            actions.setNeutralButton(R.string.model_remove, (d, w) ->
                    new AlertDialog.Builder(this).setMessage(getString(R.string.model_remove_confirm, file.getName()))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.model_remove, (confirm, button) -> removeModel(file)).show());
            actions.show();
        }).show();
    }

    private void removeModel(File target) {
        if (busy) return;
        boolean active = target.equals(modelFile);
        busy = true;
        updateControls();
        worker.execute(() -> {
            boolean success;
            try {
                if (active) NativeTts.unloadModel();
                success = modelLibrary.remove(target);
            } catch (Throwable error) {
                Log.e(TAG, "Model removal failed", error);
                success = false;
            }
            boolean removed = success;
            postToUi(() -> {
                if (removed) {
                    String saved = modelPreferences().getString(ModelLibrary.SELECTED_PATH, null);
                    if (target.getAbsolutePath().equals(saved)) {
                        modelPreferences().edit().remove(ModelLibrary.SELECTED_PATH).apply();
                    }
                    if (active) {
                        releasePlayer();
                        modelFile = null;
                        modelReady = false;
                        lastRun = null;
                        hasPerformanceReport = false;
                        parameterPanel.clear();
                        transcriptText.setText("");
                        modelName.setText(R.string.model_name);
                        modelStatus.setText(R.string.model_empty);
                    }
                } else {
                    modelStatus.setText(R.string.model_remove_failed);
                }
                setBusy(false);
            });
        });
    }

    private void confirmDownload(ModelRepository.Source source, ModelRepository.Entry entry) {
        new AlertDialog.Builder(this).setTitle(entry.name())
                .setMessage(getString(R.string.model_download_confirm, source.label,
                        Formatter.formatFileSize(this, entry.bytes())))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.download_gguf, (dialog, which) -> downloadModel(source, entry))
                .show();
    }

    private void downloadModel(ModelRepository.Source source, ModelRepository.Entry entry) {
        if (busy || downloading) return;
        try {
            ModelDownloadService.start(this, source, entry);
        } catch (RuntimeException error) {
            showError(getString(R.string.model_download_failed, readableMessage(error)));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !modelPreferences().getBoolean(DOWNLOAD_NOTIFICATION_ASKED, false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            modelPreferences().edit().putBoolean(DOWNLOAD_NOTIFICATION_ASKED, true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_DOWNLOAD_NOTIFICATION);
        }
    }

    private void renderDownload() {
        ModelDownloadService.State transfer = ModelDownloadService.current();
        if (transfer == null) return;
        downloading = transfer.active();
        updateControls();
        if (transfer.active()) {
            modelProgress.setIndeterminate(transfer.copied() == 0);
            modelProgress.setProgress(transfer.progress());
            modelProgress.setVisibility(View.VISIBLE);
            cancelModelTransfer.setEnabled(!transfer.cancelling());
            cancelModelTransfer.setVisibility(View.VISIBLE);
            if (transfer.cancelling()) {
                modelStatus.setText(R.string.model_cancelling);
            } else if (transfer.copied() == 0) {
                modelStatus.setText(getString(R.string.model_downloading, transfer.name()));
            } else {
                modelStatus.setText(getString(R.string.model_download_progress, transfer.name(),
                        Formatter.formatFileSize(this, transfer.copied()), Formatter.formatFileSize(this, transfer.total())));
            }
        } else {
            ModelDownloadService.consumeResult(this);
            File previous = restoreAfterDownload;
            restoreAfterDownload = null;
            if (transfer.model() == null && previous != null) {
                finishModelTransfer(null, transfer.message());
                selectModel(previous, transfer.message());
            } else {
                finishModelTransfer(transfer.model(), transfer.message());
            }
        }
    }

    private AtomicBoolean beginModelTransfer(String message, boolean indeterminate) {
        modelTransferCancelled = new AtomicBoolean();
        busy = true;
        updateControls();
        modelStatus.setText(message);
        modelProgress.setIndeterminate(indeterminate);
        modelProgress.setProgress(0);
        modelProgress.setVisibility(View.VISIBLE);
        cancelModelTransfer.setEnabled(true);
        cancelModelTransfer.setVisibility(View.VISIBLE);
        return modelTransferCancelled;
    }

    private String transferError(AtomicBoolean cancelled, int format, Exception error) {
        return cancelled.get() || error instanceof CancellationException
                ? getString(R.string.model_cancelled) : getString(format, readableMessage(error));
    }

    private void finishModelTransfer(File target, String message) {
        modelTransferCancelled = null;
        modelProgress.setVisibility(View.GONE);
        cancelModelTransfer.setVisibility(View.GONE);
        setBusy(false);
        if (target != null) selectModel(target);
        else {
            modelStatus.setText(message);
            modelStatus.announceForAccessibility(message);
        }
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
        if (busy) return;
        String name = queryDisplayName(source, "custom.gguf");
        AtomicBoolean cancelled = beginModelTransfer(getString(R.string.model_importing, name), true);
        worker.execute(() -> {
            File destination = null;
            try {
                destination = modelLibrary.importTarget(name);
                copyModelUri(source, destination, cancelled);
                File completed = destination;
                postToUi(() -> finishModelTransfer(completed, null));
            } catch (Exception error) {
                if (destination != null) destination.getParentFile().delete();
                postToUi(() -> finishModelTransfer(null, transferError(cancelled, R.string.model_import_failed, error)));
            }
        });
    }

    private void copyModelUri(Uri source, File destination, AtomicBoolean cancelled) throws IOException {
        File partial = new File(destination.getParentFile(), "import.part");
        try (InputStream input = getContentResolver().openInputStream(source)) {
            byte[] header = new byte[24];
            if (input == null || !ModelLibrary.readHeader(input, header)) {
                throw new IOException(getString(R.string.model_invalid));
            }
            try (FileOutputStream output = new FileOutputStream(partial)) {
                output.write(header);
                byte[] buffer = new byte[256 * 1024];
                while (!cancelled.get()) {
                    int count = input.read(buffer);
                    if (count < 0) break;
                    output.write(buffer, 0, count);
                }
                if (cancelled.get()) throw new CancellationException();
                output.getFD().sync();
            }
            if (cancelled.get()) throw new CancellationException();
            if (!partial.renameTo(destination)) throw new IOException(getString(R.string.model_save_failed));
        } finally {
            if (partial.exists()) partial.delete();
        }
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
                copyAudioUri(source, destination);
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

    private void copyAudioUri(Uri source, File destination) throws IOException {
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
            validateWav(temporary);
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

    private static void validateWav(File file) throws IOException {
        if (file.length() < 44) throw new IOException("WAV 文件为空或不完整");
        byte[] header = new byte[12];
        int count;
        try (FileInputStream input = new FileInputStream(file)) {
            count = input.read(header);
        }
        if (count < 12 || header[0] != 'R' || header[1] != 'I'
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
                : getString(requiresSpeakerReference ? R.string.reference_empty_required : R.string.reference_empty));
        clearReferenceButton.setVisibility(referenceSelected ? View.VISIBLE : View.GONE);
        asrAudioStatus.setText(asrAudioFile.isFile()
                ? getString(R.string.asr_audio_selected, asrAudioName)
                : getString(R.string.asr_audio_empty));
        updateControls();
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
        boolean busy = this.busy || downloading;
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
        inferenceControls.setVisibility(modelReady ? View.VISIBLE : View.GONE);
        modelLibraryButton.setEnabled(!busy);
        downloadModelButton.setEnabled(!busy);
        importModelButton.setEnabled(!busy);
        exportPerformanceButton.setEnabled(hasPerformanceReport && !busy);
    }

    private void runSelectedTask() {
        if (!modelReady || busy) {
            showError("模型尚未就绪");
            return;
        }
        modelStatus.setText(readyModelStatus());
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
        if (requiresSpeakerReference && !referenceFile.isFile()) {
            showError(getString(R.string.reference_empty_required));
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
        if (modelTransferCancelled != null) modelTransferCancelled.set(true);
        releasePlayer();
        worker.shutdownNow();
        catalogWorker.shutdownNow();
        super.onDestroy();
    }
}
