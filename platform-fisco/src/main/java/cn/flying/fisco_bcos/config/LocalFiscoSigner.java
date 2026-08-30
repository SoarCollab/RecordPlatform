package cn.flying.fisco_bcos.config;

import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.crypto.keypair.CryptoKeyPair;

/** Shares explicit local signer selection between the provider and deployment helper. */
public final class LocalFiscoSigner {
    /** Utility class; signing state belongs exclusively to the selected SDK client. */
    private LocalFiscoSigner() {
    }

    /** Rejects random-account fallback and installs exactly the explicit runtime key. */
    public static CryptoKeyPair explicitSigner(Client client, String privateKey) {
        String key = requirePrivateKey(privateKey);
        Integer type = client.getCryptoType();
        if (type == null || (type != 0 && type != 1) || client.getCryptoSuite().cryptoTypeConfig != type) {
            throw new IllegalArgumentException("unsupported signing mode");
        }
        CryptoKeyPair signer = client.getCryptoSuite().loadKeyPair(key);
        client.getCryptoSuite().setCryptoKeyPair(signer);
        return signer;
    }

    /** Requires a nonzero fixed-width private key without accepting account files or passwords. */
    public static String requirePrivateKey(String key) {
        if (key == null || !key.matches("(?:0[xX])?[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("explicit FISCO_PRIVATE_KEY required");
        }
        String normalized = key.replaceFirst("^0[xX]", "");
        if (normalized.matches("0+")) {
            throw new IllegalArgumentException("invalid key");
        }
        return normalized;
    }

}
