package cn.flying.service.key.rotation;

/**
 * Stable persisted lifecycle and outcome constants for automated rotation.
 */
public final class KeyRotationStates {

    public static final String POLICY_ACTIVE = "ACTIVE";
    public static final String POLICY_PAUSED = "PAUSED";
    public static final String POLICY_DISABLED = "DISABLED";

    public static final String MODE_DRY_RUN = "DRY_RUN";
    public static final String MODE_APPLY = "APPLY";

    public static final String RUN_PLANNED = "PLANNED";
    public static final String RUN_RUNNING = "RUNNING";
    public static final String RUN_PAUSED = "PAUSED";
    public static final String RUN_CANCELLED = "CANCELLED";
    public static final String RUN_COMPLETED = "COMPLETED";
    public static final String RUN_COMPLETED_WITH_FAILURES = "COMPLETED_WITH_FAILURES";
    public static final String RUN_FAILED = "FAILED";

    public static final String ITEM_PENDING = "PENDING";
    public static final String ITEM_RUNNING = "RUNNING";
    public static final String ITEM_SUCCEEDED = "SUCCEEDED";
    public static final String ITEM_SKIPPED = "SKIPPED";
    public static final String ITEM_FAILED = "FAILED";

    public static final String RETIREMENT_NOT_READY = "NOT_READY";
    public static final String RETIREMENT_READY = "READY";
    public static final String RETIREMENT_ACKNOWLEDGED = "ACKNOWLEDGED";

    private KeyRotationStates() {
    }

    /**
     * Returns whether a run can never execute more work.
     */
    public static boolean isTerminalRun(String status) {
        return RUN_CANCELLED.equals(status)
                || RUN_COMPLETED.equals(status)
                || RUN_COMPLETED_WITH_FAILURES.equals(status)
                || RUN_FAILED.equals(status);
    }
}
