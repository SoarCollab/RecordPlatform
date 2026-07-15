package cn.flying.verifier.internal;

import cn.flying.verifier.model.VerificationCode;

/** Internal fail-closed input exception converted into a stable verification report. */
public final class ProofFormatException extends RuntimeException {

    private final VerificationCode code;

    /** Creates a safe proof-format exception without retaining raw input. */
    public ProofFormatException(VerificationCode code, String message) {
        super(message);
        this.code = code;
    }

    /** Creates a safe proof-format exception with an internal cause. */
    public ProofFormatException(VerificationCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Returns the stable report code. */
    public VerificationCode code() {
        return code;
    }
}
