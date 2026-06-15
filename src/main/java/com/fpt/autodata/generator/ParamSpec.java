package com.fpt.autodata.generator;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.List;

/**
 * Self-describing parameter spec consumed by the frontend to render form fields.
 *
 * <p>Use the static factories ({@link #text(String)}, {@link #enumOf(String, List)},
 * etc.) for fluent construction.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ParamSpec {

    private final String name;
    private String label;
    private final ParamType type;
    private boolean required;
    private Object defaultValue;
    private List<String> options;
    private Integer min;
    private Integer max;
    private String placeholder;
    private String hint;

    private ParamSpec(String name, ParamType type) {
        this.name = name;
        this.label = name;
        this.type = type;
    }

    /* ----- factories ----- */

    public static ParamSpec text(String name)     { return new ParamSpec(name, ParamType.TEXT); }
    public static ParamSpec password(String name) { return new ParamSpec(name, ParamType.PASSWORD); }
    public static ParamSpec number(String name)   { return new ParamSpec(name, ParamType.NUMBER); }
    public static ParamSpec bool(String name)     { return new ParamSpec(name, ParamType.BOOLEAN); }

    public static ParamSpec enumOf(String name, List<String> options) {
        ParamSpec p = new ParamSpec(name, ParamType.ENUM);
        p.options = Collections.unmodifiableList(options);
        return p;
    }
    public static ParamSpec multiEnum(String name, List<String> options) {
        ParamSpec p = new ParamSpec(name, ParamType.MULTI_ENUM);
        p.options = Collections.unmodifiableList(options);
        return p;
    }

    /* ----- fluent setters ----- */

    public ParamSpec label(String v)        { this.label = v; return this; }
    public ParamSpec required()             { this.required = true; return this; }
    public ParamSpec defaultValue(Object v) { this.defaultValue = v; return this; }
    public ParamSpec range(int lo, int hi)  { this.min = lo; this.max = hi; return this; }
    public ParamSpec placeholder(String v)  { this.placeholder = v; return this; }
    public ParamSpec hint(String v)         { this.hint = v; return this; }

    /* ----- getters (for Jackson + registry) ----- */

    public String getName() { return name; }
    public String getLabel() { return label; }
    public ParamType getType() { return type; }
    public boolean isRequired() { return required; }
    public Object getDefaultValue() { return defaultValue; }
    public List<String> getOptions() { return options; }
    public Integer getMin() { return min; }
    public Integer getMax() { return max; }
    public String getPlaceholder() { return placeholder; }
    public String getHint() { return hint; }
}
