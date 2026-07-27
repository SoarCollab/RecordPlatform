package cn.flying.service.key;

/**
 * Canonical suite and provider identifiers shared across persistence and runtime dispatch.
 */
public final class CryptoSuiteIds {

    public static final String LEGACY_CHUNK_CHAIN = "RP-AES256-GCM-CHUNK-CHAIN-V1";
    public static final String FRAMED_AEAD_V2 = "RP-AES256-GCM-FRAMED-V2";
    public static final String LOCAL_WRAPPING = "AES-256-GCM";
    public static final String VAULT_TRANSIT_WRAPPING = "VAULT-TRANSIT-AES256-GCM96-DERIVED";
    public static final String UNSIGNED_V1 = "UNSIGNED-V1";
    public static final String ED25519_JWS_V1 = "JWS-EDDSA-ED25519-V1";
    public static final String ML_DSA_65_DRAFT = "ML-DSA-65-DRAFT";
    public static final String NO_KEM_V1 = "NONE-V1";
    public static final String ML_KEM_768_DRAFT = "ML-KEM-768-DRAFT";
    public static final String MERKLE_SHA256_V1 = "RP-MERKLE-SHA256-V1";
    public static final String SIGNED_PROOF_ZIP_V2 = "RP-SIGNED-PROOF-ZIP-V2";

    public static final String CONTENT_PROVIDER = "file-content";
    public static final String NO_PROVIDER = "none";
    public static final String MERKLE_PROVIDER = "merkle-local";
    public static final String LOCAL_ED25519_PROVIDER = "local-ed25519";
    public static final int PROVIDER_CONTRACT_V1 = 1;

    private CryptoSuiteIds() {
    }
}
