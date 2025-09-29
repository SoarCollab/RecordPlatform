package cn.flying.fisco_bcos.config;

import org.fisco.bcos.sdk.v3.BcosSDK;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.client.protocol.response.BlockNumber;
import org.fisco.bcos.sdk.v3.config.model.ConfigProperty;
import org.fisco.bcos.sdk.v3.crypto.CryptoSuite;
import org.fisco.bcos.sdk.v3.crypto.keypair.CryptoKeyPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SdkBeanConfigTest {

    @Mock
    private SystemConfig systemConfig;

    @Mock
    private BcosConfig bcosConfig;

    @Mock
    private Client client;

    @Mock
    private BcosSDK bcosSDK;

    @Mock
    private CryptoSuite cryptoSuite;

    @Mock
    private CryptoKeyPair cryptoKeyPair;

    @Mock
    private BlockNumber blockNumber;

    private SdkBeanConfig sdkBeanConfig;

    @BeforeEach
    void setUp() {
        sdkBeanConfig = new SdkBeanConfig(systemConfig, bcosConfig);
    }

    @Test
    void testConfigNetwork() {
        // Prepare test data
        Map<String, List<String>> networkConfig = new HashMap<>();
        networkConfig.put("peers", List.of("127.0.0.1:20200"));
        when(bcosConfig.getNetwork()).thenReturn(networkConfig);

        ConfigProperty configProperty = new ConfigProperty();

        // Execute
        sdkBeanConfig.configNetwork(configProperty);

        // Verify
        assertNotNull(configProperty.getNetwork());
        assertEquals(networkConfig, configProperty.getNetwork());
        verify(bcosConfig).getNetwork();
    }

    @Test
    void testConfigCryptoMaterial() {
        // Prepare test data
        Map<String, Object> cryptoMaterials = new HashMap<>();
        cryptoMaterials.put("certPath", "conf");
        cryptoMaterials.put("caCert", "ca.crt");
        cryptoMaterials.put("sslCert", "sdk.crt");
        cryptoMaterials.put("sslKey", "sdk.key");
        when(bcosConfig.getCryptoMaterial()).thenReturn(cryptoMaterials);

        ConfigProperty configProperty = new ConfigProperty();

        // Execute
        sdkBeanConfig.configCryptoMaterial(configProperty);

        // Verify
        assertNotNull(configProperty.getCryptoMaterial());
        assertEquals(cryptoMaterials, configProperty.getCryptoMaterial());
        verify(bcosConfig).getCryptoMaterial();
    }

    @Test
    void testConfigCryptoKeyPairWithHexPrivateKey() {
        // Prepare test data
        String hexPrivateKey = "0x1234567890abcdef";
        String address = "0xabc123";

        when(systemConfig.getHexPrivateKey()).thenReturn(hexPrivateKey);
        when(client.getCryptoSuite()).thenReturn(cryptoSuite);
        when(cryptoSuite.loadKeyPair(anyString())).thenReturn(cryptoKeyPair);

        // Execute
        sdkBeanConfig.configCryptoKeyPair(client);

        // Verify
        verify(systemConfig).setHexPrivateKey("1234567890abcdef");
        verify(cryptoSuite).setCryptoKeyPair(cryptoKeyPair);
        verify(cryptoSuite).loadKeyPair("1234567890abcdef");
    }

    @Test
    void testConfigCryptoKeyPairWithHexPrivateKeyNoPrefix() {
        // Prepare test data
        String hexPrivateKey = "1234567890abcdef";

        when(systemConfig.getHexPrivateKey()).thenReturn(hexPrivateKey);
        when(client.getCryptoSuite()).thenReturn(cryptoSuite);
        when(cryptoSuite.loadKeyPair(anyString())).thenReturn(cryptoKeyPair);

        // Execute
        sdkBeanConfig.configCryptoKeyPair(client);

        // Verify
        verify(systemConfig, never()).setHexPrivateKey(anyString());
        verify(cryptoSuite).setCryptoKeyPair(cryptoKeyPair);
        verify(cryptoSuite).loadKeyPair(hexPrivateKey);
    }

    @Test
    void testConfigCryptoKeyPairWithEmptyHexPrivateKey() {
        // Prepare test data
        when(systemConfig.getHexPrivateKey()).thenReturn("");

        // Execute
        sdkBeanConfig.configCryptoKeyPair(client);

        // Verify
        verify(client, never()).getCryptoSuite();
        verify(cryptoSuite, never()).setCryptoKeyPair(any());
    }

    @Test
    void testConfigCryptoKeyPairWithNullHexPrivateKey() {
        // Prepare test data
        when(systemConfig.getHexPrivateKey()).thenReturn(null);

        // Execute
        sdkBeanConfig.configCryptoKeyPair(client);

        // Verify
        verify(client, never()).getCryptoSuite();
        verify(cryptoSuite, never()).setCryptoKeyPair(any());
    }

    @Test
    void testConfigCryptoKeyPairWithUpperCasePrefix() {
        // Prepare test data
        String hexPrivateKey = "0X1234567890ABCDEF";

        when(systemConfig.getHexPrivateKey()).thenReturn(hexPrivateKey);
        when(client.getCryptoSuite()).thenReturn(cryptoSuite);
        when(cryptoSuite.loadKeyPair(anyString())).thenReturn(cryptoKeyPair);

        // Execute
        sdkBeanConfig.configCryptoKeyPair(client);

        // Verify
        verify(systemConfig).setHexPrivateKey("1234567890ABCDEF");
        verify(cryptoSuite).setCryptoKeyPair(cryptoKeyPair);
        verify(cryptoSuite).loadKeyPair("1234567890ABCDEF");
    }

    @Test
    void testClientBeanCreation() throws Exception {
        // Prepare test data
        Map<String, List<String>> networkConfig = new HashMap<>();
        networkConfig.put("peers", List.of("127.0.0.1:20200"));
        Map<String, Object> cryptoMaterials = new HashMap<>();
        cryptoMaterials.put("certPath", "conf");

        when(bcosConfig.getNetwork()).thenReturn(networkConfig);
        when(bcosConfig.getCryptoMaterial()).thenReturn(cryptoMaterials);
        when(systemConfig.getGroupId()).thenReturn("group0");
        when(systemConfig.getHexPrivateKey()).thenReturn(null);

        // Use MockedConstruction for BcosSDK
        try (MockedConstruction<BcosSDK> mockedConstruction = mockConstruction(BcosSDK.class,
                (mock, context) -> {
                    when(mock.getClient(anyString())).thenReturn(client);
                })) {

            when(client.getBlockNumber()).thenReturn(blockNumber);
            when(blockNumber.getBlockNumber()).thenReturn(BigInteger.valueOf(12345));
            when(client.getCryptoSuite()).thenReturn(cryptoSuite);
            when(cryptoSuite.getCryptoKeyPair()).thenReturn(cryptoKeyPair);
            when(cryptoKeyPair.getAddress()).thenReturn("0xuser123");
            cryptoSuite.cryptoTypeConfig = 0;

            // Execute
            Client result = sdkBeanConfig.client();

            // Verify
            assertNotNull(result);
            assertEquals(client, result);
            verify(client).getBlockNumber();
            verify(blockNumber).getBlockNumber();
        }
    }

    @Test
    void testClientBeanCreationWithGmCrypto() throws Exception {
        // Prepare test data
        Map<String, List<String>> networkConfig = new HashMap<>();
        networkConfig.put("peers", List.of("127.0.0.1:20200"));
        Map<String, Object> cryptoMaterials = new HashMap<>();
        cryptoMaterials.put("certPath", "conf");

        when(bcosConfig.getNetwork()).thenReturn(networkConfig);
        when(bcosConfig.getCryptoMaterial()).thenReturn(cryptoMaterials);
        when(systemConfig.getGroupId()).thenReturn("group0");
        when(systemConfig.getHexPrivateKey()).thenReturn("0xabc123");

        // Use MockedConstruction for BcosSDK
        try (MockedConstruction<BcosSDK> mockedConstruction = mockConstruction(BcosSDK.class,
                (mock, context) -> {
                    when(mock.getClient(anyString())).thenReturn(client);
                })) {

            when(client.getBlockNumber()).thenReturn(blockNumber);
            when(blockNumber.getBlockNumber()).thenReturn(BigInteger.valueOf(99999));
            when(client.getCryptoSuite()).thenReturn(cryptoSuite);
            when(cryptoSuite.getCryptoKeyPair()).thenReturn(cryptoKeyPair);
            when(cryptoSuite.loadKeyPair(anyString())).thenReturn(cryptoKeyPair);
            when(cryptoKeyPair.getAddress()).thenReturn("0xgmuser456");
            cryptoSuite.cryptoTypeConfig = 1; // GM crypto

            // Execute
            Client result = sdkBeanConfig.client();

            // Verify
            assertNotNull(result);
            assertEquals(client, result);
            verify(client).getBlockNumber();
            verify(systemConfig).setHexPrivateKey("abc123");
            verify(cryptoSuite).setCryptoKeyPair(cryptoKeyPair);
        }
    }
}
