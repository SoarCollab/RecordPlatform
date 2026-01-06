package cn.flying.fisco_bcos;

import org.fisco.bcos.sdk.v3.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.v3.crypto.keypair.ECDSAKeyPair;
import org.fisco.bcos.sdk.v3.crypto.keypair.SM2KeyPair;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class FiscoTest {

    /**
     * 验证 FISCO BCOS SDK 的 ECDSA/SM2 密钥对可正常生成（不依赖链节点/网络环境）。
     */
    @Test
    void shouldGenerateKeyPairs() {
        CryptoKeyPair ecdsaKeyPair = new ECDSAKeyPair().generateKeyPair();
        assertNotNull(ecdsaKeyPair.getHexPrivateKey());
        assertNotNull(ecdsaKeyPair.getHexPublicKey());
        assertNotNull(ecdsaKeyPair.getAddress());

        CryptoKeyPair sm2KeyPair = new SM2KeyPair().generateKeyPair();
        assertNotNull(sm2KeyPair.getHexPrivateKey());
        assertNotNull(sm2KeyPair.getHexPublicKey());
        assertNotNull(sm2KeyPair.getAddress());
    }
}
