package com.fpt.autodata.generator;

import java.util.List;
import java.util.Map;

/**
 * Tiny coercion helpers used by every generator to safely read parameter values
 * from the loosely-typed request map.
 */
public final class Params {

    private Params() {}

    public static String requireString(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new GeneratorException("Missing required param: " + key);
        }
        return v.toString();
    }

    public static String optString(Map<String, Object> p, String key, String fallback) {
        Object v = p.get(key);
        return (v == null || v.toString().isBlank()) ? fallback : v.toString();
    }

    public static int optInt(Map<String, Object> p, String key, int fallback) {
        Object v = p.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); }
        catch (NumberFormatException e) {
            throw new GeneratorException("Invalid number for param: " + key);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> optList(Map<String, Object> p, String key, List<String> fallback) {
        Object v = p.get(key);
        if (v == null) return fallback;
        if (v instanceof List<?> l) {
            return l.stream().map(Object::toString).toList();
        }
        if (v instanceof String s) {
            if (s.isBlank()) return fallback;
            return List.of(s.split("\\s*,\\s*"));
        }
        throw new GeneratorException("Invalid list for param: " + key);
    }
}
