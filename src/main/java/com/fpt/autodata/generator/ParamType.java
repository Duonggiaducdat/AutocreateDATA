package com.fpt.autodata.generator;

/**
 * Logical input types understood by the frontend renderer.
 * Keep names lowercase — they ship as JSON values in the catalog.
 */
public enum ParamType {
    TEXT,
    PASSWORD,
    NUMBER,
    BOOLEAN,
    ENUM,
    MULTI_ENUM
}
