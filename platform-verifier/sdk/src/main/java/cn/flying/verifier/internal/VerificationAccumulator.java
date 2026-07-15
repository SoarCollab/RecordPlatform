package cn.flying.verifier.internal;

import cn.flying.verifier.model.VerificationCheck;
import cn.flying.verifier.model.VerificationCheckStatus;
import cn.flying.verifier.model.VerificationCode;
import cn.flying.verifier.model.VerificationOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects ordered checks and reduces them to one deterministic outcome. */
public final class VerificationAccumulator {

    private final List<VerificationCheck> checks = new ArrayList<>();

    /** Adds one passing check. */
    public void pass(String id, String category, VerificationCode code, String message) {
        add(id, category, VerificationCheckStatus.PASS, code, message, Map.of());
    }

    /** Adds one passing check with bounded evidence. */
    public void pass(String id, String category, VerificationCode code, String message,
                     Map<String, String> evidence) {
        add(id, category, VerificationCheckStatus.PASS, code, message, evidence);
    }

    /** Adds one definitive invalid check. */
    public void fail(String id, String category, VerificationCode code, String message) {
        add(id, category, VerificationCheckStatus.FAIL, code, message, Map.of());
    }

    /** Adds one definitive invalid check with bounded evidence. */
    public void fail(String id, String category, VerificationCode code, String message,
                     Map<String, String> evidence) {
        add(id, category, VerificationCheckStatus.FAIL, code, message, evidence);
    }

    /** Adds one unresolved trust dependency. */
    public void indeterminate(String id, String category, VerificationCode code, String message) {
        add(id, category, VerificationCheckStatus.INDETERMINATE, code, message, Map.of());
    }

    /** Adds one safe processing error. */
    public void error(String id, String category, VerificationCode code, String message) {
        add(id, category, VerificationCheckStatus.ERROR, code, message, Map.of());
    }

    /** Returns an immutable ordered check list. */
    public List<VerificationCheck> checks() {
        return List.copyOf(checks);
    }

    /** Reduces check statuses with ERROR then FAIL then INDETERMINATE precedence. */
    public VerificationOutcome outcome() {
        if (checks.stream().anyMatch(check -> check.status() == VerificationCheckStatus.ERROR)) {
            return VerificationOutcome.ERROR;
        }
        if (checks.stream().anyMatch(check -> check.status() == VerificationCheckStatus.FAIL)) {
            return VerificationOutcome.INVALID;
        }
        if (checks.stream().anyMatch(check -> check.status() == VerificationCheckStatus.INDETERMINATE)) {
            return VerificationOutcome.INDETERMINATE;
        }
        return VerificationOutcome.VALID;
    }

    /** Adds a normalized check while bounding evidence keys and values. */
    private void add(String id, String category, VerificationCheckStatus status,
                     VerificationCode code, String message, Map<String, String> evidence) {
        LinkedHashMap<String, String> safeEvidence = new LinkedHashMap<>();
        if (evidence != null) {
            evidence.entrySet().stream().limit(16).forEach(entry -> {
                String key = bounded(entry.getKey(), 64);
                String value = bounded(entry.getValue(), 512);
                if (key != null && value != null) {
                    safeEvidence.put(key, value);
                }
            });
        }
        checks.add(new VerificationCheck(
                bounded(id, 96),
                bounded(category, 64),
                status,
                code,
                bounded(message, 512),
                safeEvidence));
    }

    /** Trims one report string and caps it to a safe display length. */
    private String bounded(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        StringBuilder safe = new StringBuilder(Math.min(value.length(), maxLength));
        for (int index = 0; index < value.length() && safe.length() < maxLength; index++) {
            char character = value.charAt(index);
            safe.append(Character.isISOControl(character) ? ' ' : character);
        }
        String normalized = safe.toString().trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
