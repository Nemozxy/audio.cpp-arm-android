package com.audiocpp.ttsdemo;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "AudioCppDemo";
    private static final String MODEL_ASSET = "models/moss-tts-nano-100m-q8_0.gguf";
    private static final int REQUEST_REFERENCE_AUDIO = 42;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private EditText inputText;
    private Button synthesizeButton;
    private Button playButton;
    private Button resetOptionsButton;
    private Button selectReferenceButton;
    private Button clearReferenceButton;
    private ProgressBar progress;
    private TextView modelName;
    private TextView modelStatus;
    private TextView referenceStatus;
    private TextView statusText;
    private LinearLayout referenceSection;
    private ParameterPanel parameterPanel;
    private File modelFile;
    private File wavFile;
    private File referenceFile;
    private String referenceName;
    private MediaPlayer player;
    private boolean modelReady;
    private boolean speakerReferenceSupported;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputText = findViewById(R.id.inputText);
        synthesizeButton = findViewById(R.id.synthesizeButton);
        playButton = findViewById(R.id.playButton);
        resetOptionsButton = findViewById(R.id.resetOptionsButton);
        selectReferenceButton = findViewById(R.id.selectReferenceButton);
        clearReferenceButton = findViewById(R.id.clearReferenceButton);
        progress = findViewById(R.id.progress);
        modelName = findViewById(R.id.modelName);
        modelStatus = findViewById(R.id.modelStatus);
        referenceStatus = findViewById(R.id.referenceStatus);
        statusText = findViewById(R.id.statusText);
        referenceSection = findViewById(R.id.referenceSection);
        parameterPanel = new ParameterPanel(this, findViewById(R.id.parametersContainer));
        modelFile = new File(getFilesDir(), MODEL_ASSET);
        wavFile = new File(getFilesDir(), "tts-output.wav");
        referenceFile = new File(getFilesDir(), "voice-reference.wav");
        referenceName = referenceFile.getName();

        synthesizeButton.setOnClickListener(view -> synthesize());
        playButton.setOnClickListener(view -> playAudio());
        selectReferenceButton.setEnabled(false);
        selectReferenceButton.setOnClickListener(view -> selectReferenceAudio());
        clearReferenceButton.setOnClickListener(view -> clearReferenceAudio());
        resetOptionsButton.setOnClickListener(view -> {
            parameterPanel.reset();
            statusText.setText(R.string.parameters_reset);
        });
        updateReferenceStatus();
        initialize();
    }

    private void initialize() {
        worker.execute(() -> {
            try {
                String version = NativeTts.nativeVersion();
                postToUi(() -> {
                    modelStatus.setText(getString(R.string.extracting_model));
                    statusText.setText(getString(R.string.extracting_model));
                });
                ensureModelExtracted();
                postToUi(() -> modelStatus.setText(R.string.detecting_model));
                JSONObject inspection = new JSONObject(NativeTts.inspectModel(modelFile.getAbsolutePath()));
                String family = inspection.getString("family");
                postToUi(() -> {
                    modelName.setText(family);
                    parameterPanel.setOptions(inspection.optJSONArray("options"));
                    speakerReferenceSupported = inspection.optBoolean("supportsSpeakerReference");
                    referenceSection.setVisibility(speakerReferenceSupported ? View.VISIBLE : View.GONE);
                    modelReady = true;
                    synthesizeButton.setEnabled(true);
                    resetOptionsButton.setEnabled(true);
                    selectReferenceButton.setEnabled(speakerReferenceSupported);
                    updateReferenceStatus();
                    modelStatus.setText(family + " · audio.cpp " + version + " · ARM64");
                    statusText.setText(R.string.model_ready);
                });
            } catch (Throwable error) {
                Log.e(TAG, "Initialization failed", error);
                postToUi(() -> showError("初始化失败：" + readableMessage(error)));
            }
        });
    }

    private void selectReferenceAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(Intent.createChooser(intent, "选择 WAV 参考音频"), REQUEST_REFERENCE_AUDIO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_REFERENCE_AUDIO && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            copyReferenceAudio(data.getData());
        }
    }

    private void copyReferenceAudio(Uri source) {
        referenceName = queryDisplayName(source);
        selectReferenceButton.setEnabled(false);
        clearReferenceButton.setEnabled(false);
        referenceStatus.setText(R.string.reference_copying);
        worker.execute(() -> {
            File temporary = new File(referenceFile.getPath() + ".tmp");
            try (InputStream input = getContentResolver().openInputStream(source);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) {
                    throw new IOException("无法读取所选音频");
                }
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
                if (temporary.length() < 44) {
                    throw new IOException("参考音频文件为空或不是有效的 WAV");
                }
                if (referenceFile.exists() && !referenceFile.delete()) {
                    throw new IOException("无法替换旧的参考音频");
                }
                if (!temporary.renameTo(referenceFile)) {
                    throw new IOException("无法保存参考音频");
                }
                postToUi(() -> {
                    selectReferenceButton.setEnabled(true);
                    updateReferenceStatus();
                    statusText.setText("参考音频已就绪，可填写 reference_text 后开始克隆");
                });
            } catch (Exception error) {
                temporary.delete();
                Log.e(TAG, "Reference audio import failed", error);
                postToUi(() -> {
                    selectReferenceButton.setEnabled(true);
                    updateReferenceStatus();
                    showError("参考音频导入失败：" + readableMessage(error));
                });
            }
        });
    }

    private String queryDisplayName(Uri source) {
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
            Log.w(TAG, "Could not read reference filename", error);
        }
        return "reference.wav";
    }

    private void clearReferenceAudio() {
        if (referenceFile.exists() && !referenceFile.delete()) {
            showError("无法移除参考音频");
            return;
        }
        referenceName = referenceFile.getName();
        updateReferenceStatus();
        statusText.setText(R.string.reference_empty);
    }

    private void updateReferenceStatus() {
        boolean selected = referenceFile.isFile();
        referenceStatus.setText(selected
                ? getString(R.string.reference_selected, referenceName)
                : getString(R.string.reference_empty));
        clearReferenceButton.setEnabled(selected);
        clearReferenceButton.setVisibility(selected ? View.VISIBLE : View.GONE);
    }

    private void ensureModelExtracted() throws IOException {
        File parent = modelFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("无法创建模型目录");
        }

        long expectedSize;
        try (AssetFileDescriptor descriptor = getAssets().openFd(MODEL_ASSET)) {
            expectedSize = descriptor.getLength();
        }
        if (modelFile.isFile() && modelFile.length() == expectedSize) {
            return;
        }

        File temporary = new File(modelFile.getPath() + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("无法清理旧的模型临时文件");
        }

        try (InputStream input = getAssets().open(MODEL_ASSET);
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
        if (modelFile.exists() && !modelFile.delete()) {
            throw new IOException("无法替换旧模型文件");
        }
        if (!temporary.renameTo(modelFile)) {
            throw new IOException("无法保存模型文件");
        }
    }

    private void synthesize() {
        String text = inputText.getText().toString().trim();
        if (!modelReady) {
            showError("模型尚未就绪");
            return;
        }
        if (text.isEmpty()) {
            showError("请先输入要合成的文字");
            return;
        }

        ParameterPanel.Values options;
        try {
            options = parameterPanel.collect();
        } catch (IllegalArgumentException error) {
            showError(error.getMessage());
            return;
        }

        String referencePath = speakerReferenceSupported && referenceFile.isFile()
                ? referenceFile.getAbsolutePath() : null;
        synthesizeButton.setEnabled(false);
        playButton.setEnabled(false);
        resetOptionsButton.setEnabled(false);
        selectReferenceButton.setEnabled(false);
        clearReferenceButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        statusText.setText(R.string.working);
        long startedAt = System.nanoTime();

        worker.execute(() -> {
            String error;
            try {
                error = NativeTts.synthesize(modelFile.getAbsolutePath(), text,
                        wavFile.getAbsolutePath(), referencePath, 4,
                        options.scopes, options.keys, options.values);
            } catch (Throwable failure) {
                Log.e(TAG, "Synthesis failed", failure);
                error = readableMessage(failure);
            }
            double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
            String resultError = error;
            postToUi(() -> {
                progress.setVisibility(View.GONE);
                synthesizeButton.setEnabled(true);
                resetOptionsButton.setEnabled(true);
                selectReferenceButton.setEnabled(speakerReferenceSupported);
                updateReferenceStatus();
                if (resultError == null) {
                    statusText.setText(String.format(Locale.getDefault(),
                            "合成完成，用时 %.1f 秒，正在播放…", elapsedSeconds));
                    playButton.setEnabled(true);
                    playAudio();
                } else {
                    showError("合成失败：" + resultError);
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
        progress.setVisibility(View.GONE);
        synthesizeButton.setEnabled(modelReady);
        resetOptionsButton.setEnabled(modelReady);
        selectReferenceButton.setEnabled(modelReady && speakerReferenceSupported);
        updateReferenceStatus();
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
