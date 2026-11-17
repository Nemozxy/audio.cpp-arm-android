package com.audiocpp.ttsdemo;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
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

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private EditText inputText;
    private Button synthesizeButton;
    private Button playButton;
    private Button resetOptionsButton;
    private ProgressBar progress;
    private TextView modelName;
    private TextView modelStatus;
    private TextView statusText;
    private ParameterPanel parameterPanel;
    private File modelFile;
    private File wavFile;
    private MediaPlayer player;
    private boolean modelReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputText = findViewById(R.id.inputText);
        synthesizeButton = findViewById(R.id.synthesizeButton);
        playButton = findViewById(R.id.playButton);
        resetOptionsButton = findViewById(R.id.resetOptionsButton);
        progress = findViewById(R.id.progress);
        modelName = findViewById(R.id.modelName);
        modelStatus = findViewById(R.id.modelStatus);
        statusText = findViewById(R.id.statusText);
        parameterPanel = new ParameterPanel(this, findViewById(R.id.parametersContainer));
        modelFile = new File(getFilesDir(), MODEL_ASSET);
        wavFile = new File(getFilesDir(), "tts-output.wav");

        synthesizeButton.setOnClickListener(view -> synthesize());
        playButton.setOnClickListener(view -> playAudio());
        resetOptionsButton.setOnClickListener(view -> {
            parameterPanel.reset();
            statusText.setText(R.string.parameters_reset);
        });
        initialize();
    }

    private void initialize() {
        worker.execute(() -> {
            try {
                String version = NativeTts.nativeVersion();
                postToUi(() -> {
                    modelStatus.setText(getString(R.string.extracting_model));
                    statusText.setText(R.string.extracting_model);
                });
                ensureModelExtracted();
                postToUi(() -> modelStatus.setText(R.string.detecting_model));
                JSONObject inspection = new JSONObject(NativeTts.inspectModel(modelFile.getAbsolutePath()));
                String family = inspection.getString("family");
                postToUi(() -> {
                    modelName.setText(family);
                    parameterPanel.setOptions(inspection.optJSONArray("options"));
                    modelReady = true;
                    synthesizeButton.setEnabled(true);
                    resetOptionsButton.setEnabled(true);
                    modelStatus.setText(family + " · audio.cpp " + version + " · ARM64");
                    statusText.setText(R.string.model_ready);
                });
            } catch (Throwable error) {
                Log.e(TAG, "Initialization failed", error);
                postToUi(() -> showError("初始化失败：" + readableMessage(error)));
            }
        });
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

        synthesizeButton.setEnabled(false);
        playButton.setEnabled(false);
        resetOptionsButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        statusText.setText(R.string.working);
        long startedAt = System.nanoTime();

        worker.execute(() -> {
            String error;
            try {
                error = NativeTts.synthesize(modelFile.getAbsolutePath(), text,
                        wavFile.getAbsolutePath(), 4,
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
