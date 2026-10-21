package com.doo.domain;

public enum WorkflowType {
    WEB_RESEARCH,
    PRODUCT_COMPARISON,
    UI_INTERACTION,
    LLM_ONLY,
    UNKNOWN;

    public boolean requiresBrowser() {
        return this == WEB_RESEARCH || this == PRODUCT_COMPARISON || this == UI_INTERACTION;
    }
}
