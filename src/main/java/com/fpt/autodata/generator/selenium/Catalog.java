package com.fpt.autodata.generator.selenium;

import java.util.List;

/**
 * Single source of truth for option vocabularies surfaced to the UI.
 * Centralised so adding a service group / location updates every generator.
 */
public final class Catalog {

    private Catalog() {}

    public static final List<String> SERVICE_GROUPS = List.of(
            "Trung tâm dữ liệu", "Internet", "Cloud", "Truyền hình", "Bảo mật"
    );

    public static final List<String> SERVICE_TYPES = List.of(
            "Colocation", "Dedicated", "Rack",
            "Internet Leased Line", "FPT Cloud", "CDN", "Anti-DDoS", "IP Transit"
    );

    public static final List<String> RACK_OPTIONS = List.of(
            "n/a", "Ít hơn 1 tủ", "Nhiều hơn hoặc bằng 1 tủ"
    );

    public static final List<String> IDC_LOCATIONS = List.of(
            "FORNIX - HN02", "FORNIX - HCM03", "TANTHUAN - TIER3"
    );

    public static final List<String> IDC_REQUEST_TYPES = List.of(
            "Colocation", "Dedicated", "Rack"
    );

    public static final List<String> CONTRACT_TYPES = List.of(
            "Trung tâm dữ liệu - Colocation",
            "Trung tâm dữ liệu - Dedicated",
            "Trung tâm dữ liệu - Rack",
            "Cloud - FPT Cloud"
    );
}
