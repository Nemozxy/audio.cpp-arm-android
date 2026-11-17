package com.audiocpp.ttsdemo;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

final class ModelOption {
    static final int REQUEST = 0;
    static final int SESSION = 1;
    static final int LOAD = 2;

    final int scope;
    final String name;
    final String valueName;
    final String description;
    final String defaultValue;
    final String minValue;
    final String maxValue;
    final boolean required;

    private ModelOption(int scope, String name, String valueName, String description,
                        String defaultValue, String minValue, String maxValue, boolean required) {
        this.scope = scope;
        this.name = name;
        this.valueName = valueName;
        this.description = description;
        this.defaultValue = defaultValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.required = required;
    }

    static ModelOption fromJson(JSONObject json) throws JSONException {
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

    boolean isBoolean() {
        String valueType = valueName.toLowerCase(Locale.ROOT).replace(" ", "");
        return isBooleanValue(defaultValue)
                || valueType.equals("bool")
                || valueType.equals("boolean")
                || valueType.equals("true|false")
                || valueType.equals("false|true");
    }

    boolean isNumeric() {
        return isNumber(defaultValue) || isNumber(minValue) || isNumber(maxValue);
    }

    private static boolean isBooleanValue(String value) {
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
    }

    private static boolean isNumber(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(value.trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
