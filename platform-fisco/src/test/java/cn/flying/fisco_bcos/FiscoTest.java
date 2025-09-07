package cn.flying.fisco_bcos;

import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.v3.crypto.keypair.ECDSAKeyPair;
import org.fisco.bcos.sdk.v3.crypto.keypair.SM2KeyPair;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@SpringBootTest
@RunWith(SpringRunner.class)
public class FiscoTest {

    @Autowired
    private Client client;

    @org.junit.Test
    public void keyGeneration() {
        //ECDSA key generation
        CryptoKeyPair ecdsaKeyPair = new ECDSAKeyPair().generateKeyPair();
        System.out.println("ecdsa private key :"+ecdsaKeyPair.getHexPrivateKey());
        System.out.println("ecdsa public key :"+ecdsaKeyPair.getHexPublicKey());
        System.out.println("ecdsa address :"+ecdsaKeyPair.getAddress());
        //SM2 key generation
        CryptoKeyPair sm2KeyPair = new SM2KeyPair().generateKeyPair();
        System.out.println("sm2 private key :"+sm2KeyPair.getHexPrivateKey());
        System.out.println("sm2 public key :"+sm2KeyPair.getHexPublicKey());
        System.out.println("sm2 address :"+sm2KeyPair.getAddress());
    }
}
