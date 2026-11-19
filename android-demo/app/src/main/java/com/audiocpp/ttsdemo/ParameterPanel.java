package com.audiocpp.ttsdemo;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders the option contract while keeping displayed defaults separate from explicit overrides. */
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

    private record Binding(ModelOption option, String label, boolean advanced, boolean referenceOnly,
                           boolean positive, String[] choices, EditText textInput, Spinner choiceInput) {
        String value() {
            if (choiceInput != null) {
                int selected = choiceInput.getSelectedItemPosition();
                return selected > 0 ? choices[selected - 1] : "";
            }
            return textInput != null ? textInput.getText().toString() : "";
        }

        boolean isRequestOption(String key) {
            return option.scope == ModelOption.REQUEST && key.equals(option.name);
        }

        void reset() {
            if (choiceInput != null) choiceInput.setSelection(0);
            if (textInput != null) {
                textInput.setText("");
                textInput.setError(null);
            }
        }
    }

    private final Context context;
    private final LinearLayout container;
    private final LinearLayout referenceContainer;
    private final LinearLayout synthesisContainer;
    private final LinearLayout transcriptionContainer;
    private final List<Binding> bindings = new ArrayList<>();
    private DemoModelProfile profile = DemoModelProfile.GENERIC;
    private LinearLayout advancedContainer;
    private Button advancedButton;
    private boolean referenceAvailable;
    private int advancedCount;

    ParameterPanel(Context context, LinearLayout container, LinearLayout referenceContainer,
                   LinearLayout synthesisContainer, LinearLayout transcriptionContainer) {
        this.context = context;
        this.container = container;
        this.referenceContainer = referenceContainer;
        this.synthesisContainer = synthesisContainer;
        this.transcriptionContainer = transcriptionContainer;
    }

    void setOptions(String family, DemoModelProfile profile, String[] languages, List<ModelOption> options) {
        clear();
        this.profile = profile;
        advancedContainer = column();
        for (int scope : new int[]{ModelOption.REQUEST, ModelOption.SESSION, ModelOption.LOAD}) {
            boolean addedHeader = false;
            for (ModelOption option : options) {
                if (option.scope != scope) continue;
                var placement = profile.placement(scope, option.name);
                if (placement == DemoModelProfile.Placement.HIDDEN) continue;
                OptionPresentation presentation = OptionPresentation.forOption(family, profile, option);
                boolean referenceOnly = placement == DemoModelProfile.Placement.REFERENCE;
                boolean advanced = placement == DemoModelProfile.Placement.DEFAULT && (scope != ModelOption.REQUEST
                        || !(presentation.common() || option.requiresInput()));
                if (advanced && !addedHeader) {
                    int header = switch (scope) {
                        case ModelOption.SESSION -> R.string.option_scope_session;
                        case ModelOption.LOAD -> R.string.option_scope_load;
                        default -> R.string.option_scope_request;
                    };
                    TextView title = text(context.getString(header), 14, true);
                    title.setPadding(0, dp(12), 0, 0);
                    advancedContainer.addView(title, matchWrap());
                    addedHeader = true;
                }
                LinearLayout parent = switch (placement) {
                    case SYNTHESIS -> synthesisContainer;
                    case TRANSCRIPTION -> transcriptionContainer;
                    case REFERENCE -> referenceContainer;
                    default -> advanced ? advancedContainer : container;
                };
                String[] choices = profile.choiceValues(scope, option.name, option.choices, languages);
                addOption(parent, family, option, presentation, referenceOnly, advanced, choices);
                if (advanced) ++advancedCount;
            }
        }
        if (advancedCount > 0) {
            advancedButton = new Button(context, null, android.R.attr.buttonBarButtonStyle);
            advancedButton.setAllCaps(false);
            advancedButton.setTextColor(context.getColor(R.color.accent));
            advancedButton.setMinHeight(dp(48));
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(8);
            container.addView(advancedButton, params);
            container.addView(advancedContainer, matchWrap());
            advancedButton.setOnClickListener(view ->
                    setAdvancedExpanded(advancedContainer.getVisibility() != View.VISIBLE));
            setAdvancedExpanded(bindings.stream().anyMatch(binding ->
                    binding.advanced && binding.option.requiresInput()));
        }
        if (bindings.isEmpty()) container.addView(text(context.getString(R.string.option_none), 14, false));
        synthesisContainer.setVisibility(synthesisContainer.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        transcriptionContainer.setVisibility(transcriptionContainer.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        setReferenceAvailable(referenceAvailable);
    }

    void clear() {
        bindings.clear();
        container.removeAllViews();
        referenceContainer.removeAllViews();
        synthesisContainer.removeAllViews();
        synthesisContainer.setVisibility(View.GONE);
        transcriptionContainer.removeAllViews();
        transcriptionContainer.setVisibility(View.GONE);
        advancedButton = null;
        advancedContainer = null;
        advancedCount = 0;
    }

    void setReferenceAvailable(boolean available) {
        referenceAvailable = available;
        referenceContainer.setVisibility(available && referenceContainer.getChildCount() > 0
                ? View.VISIBLE : View.GONE);
    }

    void setEnabled(boolean enabled) {
        if (advancedButton != null) advancedButton.setEnabled(enabled);
        for (Binding binding : bindings) {
            if (binding.textInput != null) binding.textInput.setEnabled(enabled);
            if (binding.choiceInput != null) binding.choiceInput.setEnabled(enabled);
        }
    }

    void reset() {
        for (Binding binding : bindings) binding.reset();
    }

    void resetReference() {
        for (Binding binding : bindings) {
            if (binding.referenceOnly) binding.reset();
        }
    }

    Values collect() {
        boolean embeddingOnly = profile == DemoModelProfile.QWEN_BASE && bindings.stream().anyMatch(binding ->
                binding.isRequestOption("x_vector_only_mode") && "true".equals(binding.value()));
        boolean needsReferenceText = profile.requiresReferenceText(referenceAvailable, embeddingOnly);
        int[] scopes = new int[bindings.size()];
        String[] keys = new String[bindings.size()];
        String[] values = new String[bindings.size()];
        int size = 0;
        for (Binding binding : bindings) {
            if (binding.referenceOnly && !referenceAvailable) continue;
            if (embeddingOnly && binding.isRequestOption("reference_text")) continue;
            ModelOption option = binding.option;
            String value = binding.value();
            if (value.trim().isEmpty()) {
                if (binding.isRequestOption("reference_text") && needsReferenceText) {
                    throw invalid(binding, R.string.option_reference_text_required);
                }
                if (requiresInput(option)) {
                    throw invalid(binding, option.kind == ModelOption.Kind.LIST
                            ? R.string.option_list_unsupported : R.string.option_required);
                }
                continue;
            }
            if (option.isNumeric()) {
                BigDecimal number;
                try {
                    number = option.parseNumber(value.trim());
                } catch (NumberFormatException error) {
                    throw invalid(binding, option.kind == ModelOption.Kind.INTEGER
                            ? R.string.option_invalid_integer : R.string.option_invalid_decimal);
                }
                if (!option.inRange(number) || (binding.positive && number.signum() <= 0)) {
                    throw invalid(binding, rangeText(option, binding.positive));
                }
                value = number.toString();
            } else if (option.kind == ModelOption.Kind.PATH) {
                value = value.trim();
            }
            scopes[size] = option.scope;
            keys[size] = option.name;
            values[size++] = value;
        }
        return new Values(Arrays.copyOf(scopes, size), Arrays.copyOf(keys, size), Arrays.copyOf(values, size));
    }

    private boolean requiresInput(ModelOption option) {
        return option.requiresInput() || (option.scope == ModelOption.REQUEST
                && profile.requiresPresetVoice() && "voice_id".equals(option.name));
    }

    private IllegalArgumentException invalid(Binding binding, int message) {
        return invalid(binding, context.getString(message));
    }

    private IllegalArgumentException invalid(Binding binding, String message) {
        if (binding.advanced) setAdvancedExpanded(true);
        if (binding.textInput != null) {
            binding.textInput.requestFocus();
            binding.textInput.setError(message);
        } else if (binding.choiceInput != null) {
            binding.choiceInput.requestFocus();
        }
        return new IllegalArgumentException(binding.label + "：" + message);
    }

    private void setAdvancedExpanded(boolean expanded) {
        advancedContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
        advancedButton.setText(expanded ? context.getString(R.string.option_advanced_close)
                : context.getString(R.string.option_advanced_open, advancedCount));
    }

    private void addOption(LinearLayout parent, String family, ModelOption option, OptionPresentation presentation,
                           boolean referenceOnly, boolean advanced, String[] choices) {
        String key = option.key(family);
        String name = presentation.label() == 0 ? key : context.getString(presentation.label());
        boolean required = requiresInput(option);
        TextView label = text(name + (required ? " *" : ""), 15, true);
        label.setPadding(0, dp(12), 0, 0);
        parent.addView(label, matchWrap());
        int hint = presentation.hint();
        String help = presentation.help() != 0 ? context.getString(presentation.help())
                : (hint == 0 && presentation.label() != R.string.option_preset_voice) ? option.description : "";
        addDetail(parent, help, 13);

        boolean languagePicker = profile.hasLanguagePicker(option.scope, option.name) && choices.length > 0;
        String defaultLabel = context.getString(required ? R.string.option_value_required
                : languagePicker ? R.string.option_auto_language : R.string.option_use_default);
        EditText input = null;
        Spinner spinner = null;
        if (choices.length > 0) {
            List<String> labels = new ArrayList<>();
            labels.add(!languagePicker && option.hasDefault && !option.defaultValue.isEmpty()
                    ? context.getString(R.string.option_choice_default, choiceLabel(option, option.defaultValue))
                    : defaultLabel);
            for (String value : choices) labels.add(languagePicker ? languageLabel(value) : choiceLabel(option, value));
            spinner = new Spinner(context, Spinner.MODE_DIALOG);
            spinner.setPrompt(name);
            spinner.setMinimumHeight(dp(48));
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            attachControl(parent, label, spinner);
        } else if (option.kind == ModelOption.Kind.LIST) {
            // This Demo's JNI uses the scalar setter, not audiocpp_request_set_option_array.
            addDetail(parent, context.getString(R.string.option_list_unsupported), 13);
        } else {
            input = new EditText(context);
            int inputType = InputType.TYPE_CLASS_TEXT;
            if (option.isNumeric()) {
                inputType = InputType.TYPE_CLASS_NUMBER;
                if (option.kind == ModelOption.Kind.DECIMAL) inputType |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
                if (!presentation.positive() && (option.minValue.isEmpty()
                        || new BigDecimal(option.minValue).signum() < 0)) {
                    inputType |= InputType.TYPE_NUMBER_FLAG_SIGNED;
                }
            } else if (presentation.multiline()) {
                inputType |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            } else if (option.kind == ModelOption.Kind.PATH) {
                inputType |= InputType.TYPE_TEXT_VARIATION_URI;
            }
            input.setSingleLine(!presentation.multiline());
            input.setInputType(inputType);
            if (presentation.multiline()) {
                input.setMinLines(2);
                input.setMaxLines(5);
                input.setGravity(Gravity.TOP | Gravity.START);
            } else {
                input.setMinHeight(dp(48));
            }
            input.setTextSize(15);
            input.setTextColor(context.getColor(R.color.primary));
            input.setHintTextColor(context.getColor(R.color.secondary));
            input.setHint(hint == 0 ? defaultLabel : context.getString(hint));
            input.setBackgroundResource(R.drawable.input_background);
            input.setPadding(dp(12), dp(10), dp(12), dp(10));
            attachControl(parent, label, input);

            String defaultText = hint == 0 && option.hasDefault && !option.defaultValue.isEmpty()
                    ? context.getString(R.string.option_default_value, option.defaultValue) : "";
            String range = rangeText(option, presentation.positive());
            addDetail(parent, !defaultText.isEmpty() && !range.isEmpty() ? defaultText + " · " + range
                    : defaultText + range, 12);
        }
        bindings.add(new Binding(option, name, advanced, referenceOnly, presentation.positive(), choices, input, spinner));
    }

    private void attachControl(LinearLayout parent, TextView label, View control) {
        control.setId(View.generateViewId());
        label.setLabelFor(control.getId());
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        parent.addView(control, params);
    }

    private String choiceLabel(ModelOption option, String value) {
        if (option.kind == ModelOption.Kind.BOOLEAN) {
            if ("true".equals(value)) return context.getString(R.string.option_on);
            if ("false".equals(value)) return context.getString(R.string.option_off);
        }
        if ("text_chunk_mode".equals(option.name)) {
            int label = switch (value) {
                case "default", "word_budget" -> R.string.option_chunk_default;
                case "tag_aware" -> R.string.option_chunk_tags;
                case "japanese" -> R.string.option_chunk_japanese;
                case "endline" -> R.string.option_chunk_endline;
                default -> 0;
            };
            if (label != 0) return context.getString(label);
        }
        if (profile == DemoModelProfile.QWEN_ASR && "audio_chunk_mode".equals(option.name)) {
            int label = switch (value) {
                case "auto" -> R.string.option_audio_chunk_auto;
                case "fixed" -> R.string.option_audio_chunk_fixed;
                case "none" -> R.string.option_audio_chunk_none;
                default -> 0;
            };
            if (label != 0) return context.getString(label);
        }
        return value;
    }

    private static final Map<String, String> LANGUAGE_LABELS = languageLabels();

    private static Map<String, String> languageLabels() {
        Map<String, String> labels = new HashMap<>();
        for (String code : Locale.getISOLanguages()) {
            Locale locale = Locale.forLanguageTag(code);
            String label = locale.getDisplayLanguage(Locale.SIMPLIFIED_CHINESE);
            labels.put(code, label);
            labels.put(locale.getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT), label);
        }
        for (String code : new String[]{"yue", "fil"}) {
            Locale locale = Locale.forLanguageTag(code);
            String label = locale.getDisplayLanguage(Locale.SIMPLIFIED_CHINESE);
            labels.put(code, label);
            labels.put(locale.getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT), label);
        }
        return labels;
    }

    private static String languageLabel(String value) {
        return LANGUAGE_LABELS.getOrDefault(value.toLowerCase(Locale.ROOT), value);
    }

    private String rangeText(ModelOption option, boolean positive) {
        if (positive) return context.getString(R.string.option_positive);
        if (!option.minValue.isEmpty() && !option.maxValue.isEmpty()) {
            return context.getString(R.string.option_range, option.minValue, option.maxValue);
        }
        if (!option.minValue.isEmpty()) return context.getString(R.string.option_minimum, option.minValue);
        if (!option.maxValue.isEmpty()) return context.getString(R.string.option_maximum, option.maxValue);
        return "";
    }

    private void addDetail(LinearLayout parent, String value, int size) {
        if (value.isEmpty()) return;
        TextView detail = text(value, size, false);
        detail.setPadding(0, dp(4), 0, 0);
        parent.addView(detail, matchWrap());
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(context.getColor(bold ? R.color.primary : R.color.secondary));
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
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
