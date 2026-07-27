package cn.flying.service.proof.signed;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.service.key.CryptoSuiteIds;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 基于 JCA Ed25519 的本地 proof 签名 provider。
 */
@Service
@RequiredArgsConstructor
public class LocalEd25519ProofSigningProvider implements ProofSigningProviderAdapter {

    private static final String JCA_ALGORITHM = "Ed25519";
    private static final String JWS_ALGORITHM = "EdDSA";
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final byte[] KEY_PAIR_CHALLENGE =
            "record-platform-proof-key-pair-check.v1".getBytes(StandardCharsets.UTF_8);

    private final ProofSigningProperties properties;
    private final ProofCanonicalizer canonicalizer;

    /**
     * Returns the stable local Ed25519 provider identity.
     */
    @Override
    public String providerId() {
        return CryptoSuiteIds.LOCAL_ED25519_PROVIDER;
    }

    /**
     * Returns the initial provider contract version.
     */
    @Override
    public int contractVersion() {
        return CryptoSuiteIds.PROVIDER_CONTRACT_V1;
    }

    /**
     * Returns the exact JWS Ed25519 signature suite implemented by this provider.
     */
    @Override
    public String signatureSuite() {
        return CryptoSuiteIds.ED25519_JWS_V1;
    }

    /**
     * Returns the frozen signed-proof format supported by this provider.
     */
    @Override
    public java.util.Set<String> proofSuites() {
        return java.util.Set.of(CryptoSuiteIds.SIGNED_PROOF_ZIP_V2);
    }

    /**
     * Reports whether the configured key pair is executable without exposing its identity or public material.
     */
    @Override
    public ProofSigningProviderDiagnostic diagnostics() {
        if (!properties.isEnabled()) {
            return new ProofSigningProviderDiagnostic(
                    providerId(), contractVersion(), signatureSuite(), proofSuites(),
                    false, "disabled");
        }
        try {
            loadActiveKey();
            return new ProofSigningProviderDiagnostic(
                    providerId(), contractVersion(), signatureSuite(), proofSuites(),
                    true, "configured");
        } catch (GeneralException exception) {
            return new ProofSigningProviderDiagnostic(
                    providerId(), contractVersion(), signatureSuite(), proofSuites(),
                    false, "invalid_configuration");
        }
    }

    /**
     * 仅检查全局导出开关，使应急禁用同时覆盖新签发和历史重建。
     */
    @Override
    public void requireExportEnabled() {
        if (!properties.isEnabled()) {
            throw signingUnavailable();
        }
    }

    /**
     * 解析并验证当前专用签名 key，返回不含私钥的元数据。
     */
    @Override
    public ProofSigningKeyMetadata currentKey() {
        return loadActiveKey().metadata();
    }

    /**
     * 使用当前 Ed25519 私钥生成 payload 内嵌的 compact JWS。
     */
    @Override
    public ProofSignature sign(byte[] manifest, ProofSigningKeyMetadata expectedKey) {
        if (manifest == null || manifest.length == 0 || expectedKey == null) {
            throw signingUnavailable();
        }
        ActiveKey activeKey = loadActiveKey();
        if (!activeKey.metadata().equals(expectedKey)) {
            throw new GeneralException(ResultEnum.PERMISSION_SIGNATURE_ERROR, "证明签名 key 在签发期间发生变化");
        }
        try {
            byte[] protectedHeader = canonicalizer.canonicalBytes(new JwsHeader(
                    JWS_ALGORITHM,
                    expectedKey.keyId(),
                    expectedKey.keyVersion(),
                    "JOSE"));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String encodedHeader = encoder.encodeToString(protectedHeader);
            String encodedPayload = encoder.encodeToString(manifest);
            String signingInput = encodedHeader + "." + encodedPayload;

            Signature signature = Signature.getInstance(JCA_ALGORITHM);
            signature.initSign(activeKey.privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String compactJws = signingInput + "." + encoder.encodeToString(signature.sign());
            return new ProofSignature(compactJws, activeKey.metadata());
        } catch (GeneralException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralException(ResultEnum.PERMISSION_SIGNATURE_ERROR, "证明包签名失败");
        }
    }

    /**
     * 使用签发记录保存的历史 SPKI 验证 compact JWS 和 payload。
     */
    @Override
    public boolean verify(byte[] manifest, String compactJws, ProofSigningKeyMetadata expectedKey) {
        if (manifest == null
                || !StringUtils.hasText(compactJws)
                || expectedKey == null
                || !JWS_ALGORITHM.equals(expectedKey.algorithm())
                || !StringUtils.hasText(expectedKey.keyId())
                || !KEY_ID_PATTERN.matcher(expectedKey.keyId()).matches()
                || expectedKey.keyVersion() == null
                || expectedKey.keyVersion() <= 0
                || !StringUtils.hasText(expectedKey.publicKeyFingerprint())
                || !SHA256_PATTERN.matcher(expectedKey.publicKeyFingerprint()).matches()
                || !StringUtils.hasText(expectedKey.publicKeySpki())) {
            return false;
        }
        try {
            String[] parts = compactJws.split("\\.", -1);
            if (parts.length != 3) {
                return false;
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            byte[] headerBytes = decoder.decode(parts[0]);
            byte[] payloadBytes = decoder.decode(parts[1]);
            byte[] signatureBytes = decoder.decode(parts[2]);
            byte[] expectedHeader = canonicalizer.canonicalBytes(new JwsHeader(
                    JWS_ALGORITHM,
                    expectedKey.keyId(),
                    expectedKey.keyVersion(),
                    "JOSE"));
            if (!encoder.encodeToString(headerBytes).equals(parts[0])
                    || !encoder.encodeToString(payloadBytes).equals(parts[1])
                    || !encoder.encodeToString(signatureBytes).equals(parts[2])
                    || !java.security.MessageDigest.isEqual(headerBytes, expectedHeader)
                    || !java.security.MessageDigest.isEqual(payloadBytes, manifest)) {
                return false;
            }
            PublicKey publicKey = parsePublicKey(expectedKey.publicKeySpki());
            if (!Objects.equals(expectedKey.publicKeyFingerprint(), canonicalizer.sha256(publicKey.getEncoded()))) {
                return false;
            }
            Signature verifier = Signature.getInstance(JCA_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 读取配置、校验 key 状态/格式/版本和密钥对一致性。
     */
    private ActiveKey loadActiveKey() {
        if (!properties.isEnabled()
                || !JCA_ALGORITHM.equals(properties.getAlgorithm())
                || !ACTIVE_STATUS.equals(properties.getKeyStatus())
                || !StringUtils.hasText(properties.getKeyId())
                || !KEY_ID_PATTERN.matcher(properties.getKeyId()).matches()
                || properties.getKeyVersion() == null
                || properties.getKeyVersion() <= 0
                || !StringUtils.hasText(properties.getPrivateKeyPkcs8())
                || !StringUtils.hasText(properties.getPublicKeySpki())) {
            throw signingUnavailable();
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(JCA_ALGORITHM);
            PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(
                    decodePemOrBase64(properties.getPrivateKeyPkcs8())));
            PublicKey publicKey = parsePublicKey(properties.getPublicKeySpki());
            validateKeyPair(privateKey, publicKey);

            String publicKeySpki = Base64.getEncoder().encodeToString(publicKey.getEncoded());
            ProofSigningKeyMetadata metadata = new ProofSigningKeyMetadata(
                    providerId(),
                    contractVersion(),
                    signatureSuite(),
                    CryptoSuiteIds.SIGNED_PROOF_ZIP_V2,
                    JWS_ALGORITHM,
                    properties.getKeyId(),
                    properties.getKeyVersion(),
                    publicKeySpki,
                    canonicalizer.sha256(publicKey.getEncoded()));
            return new ActiveKey(privateKey, metadata);
        } catch (GeneralException e) {
            throw e;
        } catch (Exception e) {
            throw signingUnavailable();
        }
    }

    /**
     * 解析 Base64 或 PEM 包装的 X.509 SPKI 公钥。
     */
    private PublicKey parsePublicKey(String value) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance(JCA_ALGORITHM);
        return keyFactory.generatePublic(new X509EncodedKeySpec(decodePemOrBase64(value)));
    }

    /**
     * 用固定挑战值确认配置的私钥和公钥属于同一密钥对。
     */
    private void validateKeyPair(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        Signature signer = Signature.getInstance(JCA_ALGORITHM);
        signer.initSign(privateKey);
        signer.update(KEY_PAIR_CHALLENGE);

        Signature verifier = Signature.getInstance(JCA_ALGORITHM);
        verifier.initVerify(publicKey);
        verifier.update(KEY_PAIR_CHALLENGE);
        if (!verifier.verify(signer.sign())) {
            throw signingUnavailable();
        }
    }

    /**
     * 去除 PEM 包装后解码 Base64 key material，不把原值写入异常。
     */
    private byte[] decodePemOrBase64(String value) {
        String normalized = Objects.requireNonNull(value)
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }

    /**
     * 生成不包含底层密钥或解析细节的统一失败异常。
     */
    private GeneralException signingUnavailable() {
        return new GeneralException(ResultEnum.PERMISSION_SIGNATURE_ERROR, "证明包专用签名 key 不可用");
    }

    /**
     * JWS protected header，canonicalizer 会按属性名排序。
     */
    private record JwsHeader(
            String alg,
            String kid,
            Integer keyVersion,
            String typ
    ) {
    }

    /**
     * 仅在单次调用栈内存在的私钥与公开元数据组合。
     */
    private record ActiveKey(
            PrivateKey privateKey,
            ProofSigningKeyMetadata metadata
    ) {
    }
}
