package com.audiocpp.ttsdemo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

final class ModelCatalogDialog {
    private static final String SAVED_SOURCE = "catalog-source";
    private final Activity activity;
    private final SharedPreferences preferences;
    private final ExecutorService worker;
    private final ModelCatalog catalog;
    private final BiConsumer<ModelRepository.Source, ModelRepository.Entry> onSelected;
    private final List<ModelRepository.Entry> visible = new ArrayList<>();
    private List<ModelRepository.Entry> entries = Collections.emptyList();
    private ArrayAdapter<String> rows;
    private AlertDialog dialog;
    private EditText search;
    private TextView status;
    private Button retry;
    private ModelRepository.Source source;
    private Future<?> request;
    private int generation;
    private boolean loaded;
    private String catalogNotice = "";

    ModelCatalogDialog(Activity activity, SharedPreferences preferences, ExecutorService worker,
                       ModelCatalog catalog,
                       BiConsumer<ModelRepository.Source, ModelRepository.Entry> onSelected) {
        this.activity = activity;
        this.preferences = preferences;
        this.worker = worker;
        this.catalog = catalog;
        this.onSelected = onSelected;
    }

    void show() {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), 0, dp(20), 0);
        body.setFocusableInTouchMode(true);

        Spinner sources = new Spinner(activity);
        sources.setContentDescription(activity.getString(R.string.model_source));
        sources.setMinimumHeight(dp(48));
        ArrayAdapter<ModelRepository.Source> sourceRows = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, ModelRepository.Source.values());
        sourceRows.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sources.setAdapter(sourceRows);
        ModelRepository.Source initial = ModelRepository.Source.HF;
        String savedSource = preferences.getString(SAVED_SOURCE, "");
        for (ModelRepository.Source option : ModelRepository.Source.values()) {
            if (option.name().equals(savedSource)) initial = option;
        }
        sources.setSelection(initial.ordinal());
        body.addView(sources);

        search = new EditText(activity);
        search.setSingleLine(true);
        search.setHint(R.string.model_search);
        body.addView(search);

        status = new TextView(activity);
        status.setPadding(0, dp(8), 0, dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status);

        retry = new Button(activity);
        retry.setText(R.string.model_retry);
        retry.setOnClickListener(view -> load(source));
        retry.setVisibility(View.GONE);
        body.addView(retry);

        ListView list = new ListView(activity);
        rows = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1);
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            ModelRepository.Entry entry = visible.get(position);
            dialog.dismiss();
            onSelected.accept(source, entry);
        });
        int height = Math.min(360, activity.getResources().getConfiguration().screenHeightDp * 2 / 5);
        body.addView(list, new LinearLayout.LayoutParams(-1, dp(height)));

        dialog = new AlertDialog.Builder(activity).setTitle(ModelRepository.REPO).setView(body)
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnDismissListener(ignored -> {
            generation++;
            if (request != null) request.cancel(true);
        });
        dialog.show();
        body.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable editable) {}
        });
        sources.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ModelRepository.Source selected = sourceRows.getItem(position);
                if (source != selected) load(selected);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        load(initial);
    }

    private void load(ModelRepository.Source nextSource) {
        source = nextSource;
        preferences.edit().putString(SAVED_SOURCE, nextSource.name()).apply();
        int token = ++generation;
        if (request != null) request.cancel(true);
        loaded = false;
        catalogNotice = "";
        entries = Collections.emptyList();
        filter();
        retry.setVisibility(View.GONE);
        status.setText(R.string.model_catalog_loading);
        request = worker.submit(() -> {
            try {
                ModelCatalog.Listing result = catalog.list(nextSource);
                activity.runOnUiThread(() -> {
                    if (generation != token || activity.isDestroyed() || !dialog.isShowing()) return;
                    entries = result.entries();
                    if (result.origin() != nextSource) {
                        catalogNotice = activity.getString(R.string.model_catalog_mirror_fallback);
                    } else if (result.stale()) {
                        catalogNotice = activity.getString(R.string.model_catalog_cached);
                    }
                    loaded = true;
                    filter();
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (generation != token || activity.isDestroyed() || !dialog.isShowing()) return;
                    if (error instanceof ModelRepository.RateLimited limit) {
                        status.setText(activity.getString(R.string.model_catalog_rate_limited, limit.retrySeconds));
                    } else {
                        String message = error.getLocalizedMessage();
                        status.setText(activity.getString(R.string.model_catalog_failed,
                                message == null ? error.getClass().getSimpleName() : message));
                    }
                    retry.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void filter() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        List<String> labels = new ArrayList<>();
        for (ModelRepository.Entry entry : entries) {
            if (entry.path().toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(entry);
                labels.add(entry.path() + "\n" + Formatter.formatFileSize(activity, entry.bytes()));
            }
        }
        rows.setNotifyOnChange(false);
        rows.clear();
        rows.addAll(labels);
        rows.notifyDataSetChanged();
        if (loaded) {
            String count = visible.isEmpty() ? activity.getString(R.string.model_catalog_empty)
                    : activity.getString(R.string.model_catalog_count, visible.size());
            status.setText(catalogNotice.isEmpty() ? count : count + "\n" + catalogNotice);
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
