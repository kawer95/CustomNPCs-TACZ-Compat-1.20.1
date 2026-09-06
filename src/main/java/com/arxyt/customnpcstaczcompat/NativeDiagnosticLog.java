package com.arxyt.customnpcstaczcompat;

/**
 * Explicit opt-in gate for verbose combat diagnostics.
 *
 * <p>These traces inspect every active gunner and can generate thousands of log records in a
 * populated battle. They are disabled unless a developer deliberately starts Java with
 * {@code -Dcustomnpcs_tacz_compat.diagnostics=true}.</p>
 */
public final class NativeDiagnosticLog {
    static final String PROPERTY = "customnpcs_tacz_compat.diagnostics";

    private NativeDiagnosticLog() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }
}
