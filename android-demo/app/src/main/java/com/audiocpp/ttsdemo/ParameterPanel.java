package com.audiocpp.ttsdemo;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Builds controls exclusively from model-owned option metadata. */
final class ParameterPanel {
    static final class Values {
        final int[] scopes;
        final String[] keys;
        final String[] values;

        private Values(int[] scopes, String[] keys, String[] values) {
            this.scopes = scopes;
            this.keys = keys;
            this.values = values;
        }
    }

    private static final class Binding {
        final ModelOption option;
        final EditText textInput;
        final Spinner booleanInput;

        Binding(ModelOption option, EditText textInput, Spinner booleanInput) {
            this.option = option;
            this.textInput = textInput;
            this.booleanInput = booleanInput;
        }

        String value() {
            if (booleanInput != null) {
                int selected = booleanInput.getSelectedItemPosition();
                return selected == 0 ? "" : booleanInput.getSelectedItem().toString();
            }
            return textInput.getText().toString();
        }

        void reset() {
            if (booleanInput != null) {
                booleanInput.setSelection(0);
            } else {
                textInput.setText("");
            }
        }
    }

    private final Context context;
    private final LinearLayout container;
    private final List<Binding> bindings = new ArrayList<>();

    ParameterPanel(Context context, LinearLayout container) {
        this.context = context;
        this.container = container;
    }

    void setOptions(JSONArray json) {
        try {
            List<ModelOption> options = new ArrayList<>();
            for (int index = 0; index < json.length(); ++index) {
                options.add(ModelOption.fromJson(json.getJSONObject(index)));
            }
            bindings.clear();
            container.removeAllViews();
            addScope(options, ModelOption.REQUEST,
                    "请求参数", "每次推理生效；留空使用模型默认值");
            addScope(options, ModelOption.SESSION,
                    "会话参数", "修改后自动重建会话，模型无需重新下载");
            addScope(options, ModelOption.LOAD,
                    "加载参数", "修改后自动重新加载模型并重建会话");
            if (options.isEmpty()) {
                TextView empty = text("该模型未声明额外参数", 14, false);
                container.addView(empty);
            }
        } catch (JSONException error) {
            throw new IllegalStateException("模型参数元数据格式无效", error);
        }
    }

    void reset() {
        for (Binding binding : bindings) {
            binding.reset();
        }
    }

    Values collect() {
        List<Integer> scopes = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (Binding binding : bindings) {
            String value = binding.value();
            if (value.trim().isEmpty()) {
                if (binding.option.required && binding.option.defaultValue.isEmpty()) {
                    throw new IllegalArgumentException("请填写必填参数：" + binding.option.name);
                }
                continue;
            }
            scopes.add(binding.option.scope);
            keys.add(binding.option.name);
            values.add(value);
        }

        int[] scopeArray = new int[scopes.size()];
        String[] keyArray = new String[scopes.size()];
        String[] valueArray = new String[scopes.size()];
        for (int index = 0; index < scopes.size(); ++index) {
            scopeArray[index] = scopes.get(index);
            keyArray[index] = keys.get(index);
            valueArray[index] = values.get(index);
        }
        return new Values(scopeArray, keyArray, valueArray);
    }

    private void addScope(List<ModelOption> options, int scope, String title, String subtitle) {
        boolean addedHeader = false;
        for (ModelOption option : options) {
            if (option.scope != scope) {
                continue;
            }
            if (!addedHeader) {
                TextView header = text(title, 18, true);
                header.setPadding(0, dp(20), 0, 0);
                container.addView(header, matchWrap());
                TextView description = text(subtitle, 13, false);
                description.setPadding(0, dp(4), 0, dp(6));
                container.addView(description, matchWrap());
                addedHeader = true;
            }
            addOption(option);
        }
    }

    private void addOption(ModelOption option) {
        TextView label = text(option.name + (option.required ? " *" : ""), 15, true);
        label.setPadding(0, dp(12), 0, 0);
        container.addView(label, matchWrap());

        if (!option.description.isEmpty()) {
            TextView description = text(option.description, 13, false);
            description.setPadding(0, dp(4), 0, 0);
            container.addView(description, matchWrap());
        }

        EditText textInput = null;
        Spinner booleanInput = null;
        if (option.isBoolean()) {
            booleanInput = new Spinner(context);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_item,
                    Arrays.asList("默认", "true", "false"));
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            booleanInput.setAdapter(adapter);
            booleanInput.setContentDescription(option.name);
            booleanInput.setPadding(0, dp(8), 0, dp(8));
            container.addView(booleanInput, matchWrap());
        } else {
            textInput = new EditText(context);
            textInput.setSingleLine(true);
            textInput.setTextSize(15);
            textInput.setTextColor(context.getColor(R.color.primary));
            textInput.setHintTextColor(context.getColor(R.color.secondary));
            textInput.setContentDescription(option.name);
            textInput.setHint(option.defaultValue.isEmpty()
                    ? (option.valueName.isEmpty() ? "留空使用模型默认值" : option.valueName)
                    : "默认：" + option.defaultValue);
            if (option.isNumeric()) {
                textInput.setInputType(InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
            } else {
                textInput.setInputType(InputType.TYPE_CLASS_TEXT);
            }
            textInput.setBackgroundResource(R.drawable.input_background);
            textInput.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams inputParams = matchWrap();
            inputParams.topMargin = dp(8);
            container.addView(textInput, inputParams);
        }

        StringBuilder metadata = new StringBuilder();
        if (!option.defaultValue.isEmpty()) {
            metadata.append("默认值：").append(option.defaultValue);
        }
        if (!option.minValue.isEmpty() || !option.maxValue.isEmpty()) {
            if (metadata.length() > 0) metadata.append(" · ");
            metadata.append("范围：").append(option.minValue).append(" ～ ").append(option.maxValue);
        }
        if (!option.valueName.isEmpty() && option.defaultValue.isEmpty() && !option.isBoolean()) {
            if (metadata.length() > 0) metadata.append(" · ");
            metadata.append("值：").append(option.valueName);
        }
        if (metadata.length() > 0) {
            TextView metadataView = text(metadata.toString(), 12, false);
            metadataView.setPadding(0, dp(4), 0, 0);
            container.addView(metadataView, matchWrap());
        }
        bindings.add(new Binding(option, textInput, booleanInput));
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(context.getColor(bold ? R.color.primary : R.color.secondary));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
