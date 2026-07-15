package cn.flying.verifier;

import cn.flying.verifier.model.VerificationReport;

import java.nio.file.Path;

/** Public SDK boundary for signed proof ZIP verification. */
@FunctionalInterface
public interface ProofVerifier {

    /**
     * Verifies an original file and signed proof archive without platform session or persistence state.
     *
     * @param originalFile original file path
     * @param proofArchive signed proof ZIP path
     * @param context explicit limits and trust resolvers
     * @return complete stable report
     */
    VerificationReport verify(Path originalFile, Path proofArchive, VerificationContext context);
}
