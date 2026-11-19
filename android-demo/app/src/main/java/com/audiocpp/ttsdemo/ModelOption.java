package com.audiocpp.ttsdemo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ModelOption {
    static final int REQUEST = 0;
    static final int SESSION = 1;
    static final int LOAD = 2;

    enum Kind { BOOLEAN, INTEGER, DECIMAL, TEXT, PATH, CHOICE, LIST }

    final int scope;
    final String name;
    final Kind kind;
    final String[] choices;
    final String description;
    final boolean hasDefault;
    final String defaultValue;
    final String minValue;
    final String maxValue;
    final boolean required;

    private ModelOption(int scope, String name, String valueName, String description,
                        String defaultJson, String minValue, String maxValue, boolean required)
            throws JSONException {
        this.scope = scope;
        this.name = name;
        // Handwritten loaders still use CLI metavariables such as n and text.
        this.kind = switch (valueName.toLowerCase(Locale.ROOT)) {
            case "bool", "boolean", "true|false", "false|true" -> Kind.BOOLEAN;
            case "int", "<int>", "n", "mb" -> Kind.INTEGER;
            case "float", "<float>", "seconds" -> Kind.DECIMAL;
            case "", "string", "text", "value", "enum" -> Kind.TEXT;
            case "path", "audio_path" -> Kind.PATH;
            case "string_list", "path_list" -> Kind.LIST;
            // The native metadata flattens enum members into valueName, including singletons.
            default -> Kind.CHOICE;
        };
        this.choices = switch (kind) {
            case BOOLEAN -> new String[]{"true", "false"};
            case CHOICE -> valueName.split("\\|", -1);
            default -> new String[0];
        };
        this.description = description;
        this.hasDefault = !defaultJson.isEmpty() && !"null".equals(defaultJson);
        this.defaultValue = defaultJson.startsWith("\"")
                ? (String) new JSONTokener(defaultJson).nextValue() : defaultJson;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.required = required;
    }

    static List<ModelOption> readOptions(JSONArray json) throws JSONException {
        List<ModelOption> options = new ArrayList<>(json.length());
        for (int index = 0; index < json.length(); ++index) {
            options.add(fromJson(json.getJSONObject(index)));
        }
        return options;
    }

    private static ModelOption fromJson(JSONObject json) throws JSONException {
        return new ModelOption(
                json.getInt("scope"),
                json.getString("name"),
                json.getString("valueName"),
                json.getString("description"),
                json.getString("defaultValue"),
                json.getString("minValue"),
                json.getString("maxValue"),
                json.getBoolean("required"));
    }

    String key(String family) {
        String prefix = family + ".";
        return scope != REQUEST && name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }

    boolean isNumeric() {
        return kind == Kind.INTEGER || kind == Kind.DECIMAL;
    }

    boolean requiresInput() {
        return required && !hasDefault;
    }

    BigDecimal parseNumber(String value) {
        if (kind == Kind.INTEGER) {
            if (!value.matches("[+-]?[0-9]+")) throw new NumberFormatException();
            return BigDecimal.valueOf(Long.parseLong(value));
        }
        BigDecimal number = new BigDecimal(value);
        if (!Double.isFinite(number.doubleValue())) throw new NumberFormatException();
        return number;
    }

    boolean inRange(BigDecimal number) {
        return (minValue.isEmpty() || number.compareTo(new BigDecimal(minValue)) >= 0)
                && (maxValue.isEmpty() || number.compareTo(new BigDecimal(maxValue)) <= 0);
    }
}
