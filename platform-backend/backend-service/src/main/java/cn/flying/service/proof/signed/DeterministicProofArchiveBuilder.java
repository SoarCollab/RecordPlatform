package cn.flying.service.proof.signed;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.verifier.contract.SignedProofBundleContract;
import cn.flying.verifier.contract.SignedProofBundleModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 先摘要证据条目、再 canonicalize manifest、最后签名并组装确定性 ZIP。
 */
@Service
@RequiredArgsConstructor
public class DeterministicProofArchiveBuilder {

    public static final String MANIFEST_SCHEMA = SignedProofBundleContract.MANIFEST_SCHEMA;

    private static final String JSON_MEDIA_TYPE = SignedProofBundleContract.JSON_MEDIA_TYPE;
    private static final String TEXT_MEDIA_TYPE = SignedProofBundleContract.TEXT_MEDIA_TYPE;
    private static final String MARKDOWN_MEDIA_TYPE = SignedProofBundleContract.MARKDOWN_MEDIA_TYPE;
    private static final String JWS_MEDIA_TYPE = SignedProofBundleContract.JWS_MEDIA_TYPE;

    private final ProofCanonicalizer canonicalizer;
    private final ProofSigningProvider signingProvider;

    /**
     * 使用当前 ACTIVE key 新签发 proof archive。
     *
     * @param fileName 安全下载名
     * @param seed 不可变签发上下文
     * @param payloads 证据 payload
     * @return 完整签名 archive
     */
    public ProofArchive buildNew(
            String fileName,
            SignedProofBundleModel.ManifestSeed seed,
            SignedProofBundleModel.EvidencePayloads payloads
    ) {
        ProofSigningKeyMetadata key = signingProvider.currentKey();
        return buildNew(fileName, seed, payloads, key);
    }

    /**
     * 使用调用方已绑定到 manifest 的 ACTIVE key 新签发，阻断元数据读取和签名之间的轮换竞态。
     *
     * @param fileName 安全下载名
     * @param seed 不可变签发上下文
     * @param payloads 证据 payload
     * @param key 已绑定的当前 ACTIVE key
     * @return 完整签名 archive
     */
    public ProofArchive buildNew(
            String fileName,
            SignedProofBundleModel.ManifestSeed seed,
            SignedProofBundleModel.EvidencePayloads payloads,
            ProofSigningKeyMetadata key
    ) {
        PreparedArchive prepared = prepare(fileName, seed, payloads, key);
        ProofSignature signature = signingProvider.sign(prepared.manifestBytes(), key);
        if (!key.equals(signature.key())) {
            throw new GeneralException(ResultEnum.PERMISSION_SIGNATURE_ERROR, "证明包签名 key 不一致");
        }
        return assemble(prepared, signature.compactJws());
    }

    /**
     * 使用签发记录中的历史 key/JWS 重建 archive，并验证 payload 未发生漂移。
     *
     * @param fileName 安全下载名
     * @param seed 原始签发上下文
     * @param payloads 当前重新校验后的证据 payload
     * @param key 历史公开 key 元数据
     * @param compactJws 历史 compact JWS
     * @return 与首次签发逻辑一致的 archive
     */
    public ProofArchive rebuild(
            String fileName,
            SignedProofBundleModel.ManifestSeed seed,
            SignedProofBundleModel.EvidencePayloads payloads,
            ProofSigningKeyMetadata key,
            String compactJws
    ) {
        PreparedArchive prepared = prepare(fileName, seed, payloads, key);
        if (!signingProvider.verify(prepared.manifestBytes(), compactJws, key)) {
            throw new GeneralException(ResultEnum.PERMISSION_SIGNATURE_ERROR, "历史证明包签名校验失败");
        }
        return assemble(prepared, compactJws);
    }

    /**
     * 构建所有证据 entry 摘要与 canonical manifest，但暂不生成签名 entry。
     */
    private PreparedArchive prepare(
            String fileName,
            SignedProofBundleModel.ManifestSeed seed,
            SignedProofBundleModel.EvidencePayloads payloads,
            ProofSigningKeyMetadata key
    ) {
        if (seed == null || payloads == null || key == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "证明包签发输入不完整");
        }
        List<ProofArchive.ArchiveEntry> evidenceEntries = buildEvidenceEntries(payloads);
        List<SignedProofBundleModel.EntryDigest> digests = evidenceEntries.stream()
                .map(entry -> new SignedProofBundleModel.EntryDigest(
                        entry.name(),
                        entry.mediaType(),
                        canonicalizer.sha256(entry.bytes()),
                        entry.bytes().length,
                        true,
                        true))
                .toList();

        SignedProofBundleModel.Manifest manifest = new SignedProofBundleModel.Manifest(
                MANIFEST_SCHEMA,
                seed.proofId(),
                seed.fileId(),
                seed.fileVersion(),
                seed.leafId(),
                seed.batchNo(),
                seed.issuedAt(),
                seed.issuedStatus(),
                seed.statusLocation(),
                new SignedProofBundleModel.SignatureMetadata(
                        key.algorithm(),
                        key.keyId(),
                        key.keyVersion(),
                        key.publicKeyFingerprint(),
                        "/api/v1/public/proof-keys/" + key.keyId() + "/versions/" + key.keyVersion()),
                digests);
        byte[] manifestBytes = canonicalizer.canonicalBytes(manifest);
        return new PreparedArchive(
                fileName,
                manifestBytes,
                canonicalizer.sha256(manifestBytes),
                evidenceEntries);
    }

    /**
     * 按固定逻辑顺序 canonicalize 六个被 manifest 摘要绑定的证据条目。
     */
    private List<ProofArchive.ArchiveEntry> buildEvidenceEntries(
            SignedProofBundleModel.EvidencePayloads payloads
    ) {
        requireCompleteEvidencePayloads(payloads);
        String canonicalContentHash = payloads.contentHash() == null
                ? ""
                : payloads.contentHash().trim().toLowerCase(Locale.ROOT);
        if (!canonicalContentHash.matches("^sha256:[0-9a-f]{64}$")) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "证明包 contentHash 不合法");
        }
        return List.of(
                new ProofArchive.ArchiveEntry(
                        ProofArchive.FILE_HASH_ENTRY,
                        TEXT_MEDIA_TYPE,
                        (canonicalContentHash + "\n").getBytes(StandardCharsets.UTF_8)),
                new ProofArchive.ArchiveEntry(
                        ProofArchive.CHUNK_MANIFEST_ENTRY,
                        JSON_MEDIA_TYPE,
                        canonicalizer.canonicalBytes(payloads.chunkManifest())),
                new ProofArchive.ArchiveEntry(
                        ProofArchive.MERKLE_PROOF_ENTRY,
                        JSON_MEDIA_TYPE,
                        canonicalizer.canonicalBytes(payloads.merkleProof())),
                new ProofArchive.ArchiveEntry(
                        ProofArchive.BLOCKCHAIN_RECEIPT_ENTRY,
                        JSON_MEDIA_TYPE,
                        canonicalizer.canonicalBytes(payloads.blockchainReceipt())),
                new ProofArchive.ArchiveEntry(
                        ProofArchive.VERIFICATION_POLICY_ENTRY,
                        JSON_MEDIA_TYPE,
                        canonicalizer.canonicalBytes(payloads.verificationPolicy())),
                new ProofArchive.ArchiveEntry(
                        ProofArchive.README_ENTRY,
                        MARKDOWN_MEDIA_TYPE,
                        payloads.readme().getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 在 manifest 把六个证据条目标记为 required/present 前拒绝缺失的结构化证据。
     *
     * @param payloads 待签名证据集合
     */
    private void requireCompleteEvidencePayloads(SignedProofBundleModel.EvidencePayloads payloads) {
        if (payloads == null
                || payloads.chunkManifest() == null
                || payloads.merkleProof() == null
                || payloads.blockchainReceipt() == null
                || payloads.verificationPolicy() == null
                || payloads.readme() == null
                || payloads.readme().isBlank()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "证明包必需证据不完整");
        }
    }

    /**
     * 把 manifest、证据和 compact JWS 放入固定八条目顺序。
     */
    private ProofArchive assemble(PreparedArchive prepared, String compactJws) {
        List<ProofArchive.ArchiveEntry> entries = new ArrayList<>(ProofArchive.ENTRY_ORDER.size());
        entries.add(new ProofArchive.ArchiveEntry(
                ProofArchive.MANIFEST_ENTRY,
                JSON_MEDIA_TYPE,
                prepared.manifestBytes()));
        entries.add(prepared.evidenceEntries().get(0));
        entries.add(prepared.evidenceEntries().get(1));
        entries.add(prepared.evidenceEntries().get(2));
        entries.add(prepared.evidenceEntries().get(3));
        entries.add(new ProofArchive.ArchiveEntry(
                ProofArchive.SIGNATURE_ENTRY,
                JWS_MEDIA_TYPE,
                (compactJws + "\n").getBytes(StandardCharsets.US_ASCII)));
        entries.add(prepared.evidenceEntries().get(4));
        entries.add(prepared.evidenceEntries().get(5));
        return new ProofArchive(
                prepared.fileName(),
                prepared.manifestHash(),
                compactJws,
                entries);
    }

    /** canonical manifest 与已摘要证据条目的中间结果。 */
    private record PreparedArchive(
            String fileName,
            byte[] manifestBytes,
            String manifestHash,
            List<ProofArchive.ArchiveEntry> evidenceEntries
    ) {
        private PreparedArchive {
            manifestBytes = manifestBytes.clone();
            evidenceEntries = List.copyOf(evidenceEntries);
        }

        @Override
        public byte[] manifestBytes() {
            return manifestBytes.clone();
        }
    }
}
