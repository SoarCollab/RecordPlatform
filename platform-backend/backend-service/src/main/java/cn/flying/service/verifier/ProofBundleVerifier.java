package cn.flying.service.verifier;

import cn.flying.dao.vo.file.ProofBundleVO;

/**
 * Verifies exported proof bundles without platform session, tenant context, or database access.
 */
public interface ProofBundleVerifier {

    /**
     * Verifies original file bytes against a parsed proof bundle.
     *
     * @param originalFile original file bytes supplied by the verifier user
     * @param bundle parsed proof bundle
     * @return structured verification result
     */
    ProofVerificationResult verify(byte[] originalFile, ProofBundleVO bundle);

    /**
     * Parses and verifies original file bytes against a proof bundle JSON document.
     *
     * @param originalFile original file bytes supplied by the verifier user
     * @param bundleJson exported proof bundle JSON
     * @return structured verification result
     */
    ProofVerificationResult verify(byte[] originalFile, String bundleJson);
}
