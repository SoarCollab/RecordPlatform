package cn.flying.monitor.client.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.SSLContext;
import java.nio.file.Path;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for certificate-based authentication functionality
 */
class CertificateManagerTest {

    private CertificateManager certificateManager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        certificateManager = new CertificateManager();
        
        // Set test configuration
        ReflectionTestUtils.setField(certificateManager, "keystorePath", 
                tempDir.resolve("test-keystore.p12").toString());
        ReflectionTestUtils.setField(certificateManager, "keystorePassword", "testpass");
        ReflectionTestUtils.setField(certificateManager, "keyAlias", "test-key");
        ReflectionTestUtils.setField(certificateManager, "validityDays", 365);
        ReflectionTestUtils.setField(certificateManager, "autoRotationDays", 30);
        ReflectionTestUtils.setField(certificateManager, "clientId", "test-client");
    }

    @Test
    void testInitializeGeneratesNewCertificate() throws Exception {
        // When
        certificateManager.initialize();

        // Then
        assertNotNull(certificateManager.getClientCertificate());
        assertNotNull(certificateManager.getPrivateKey());
        assertTrue(certificateManager.isCertificateValid());
    }

    @Test
    void testCertificateFingerprint() throws Exception {
        // Given
        certificateManager.initialize();

        // When
        String fingerprint = certificateManager.getCertificateFingerprint();

        // Then
        assertNotNull(fingerprint);
        assertEquals(64, fingerprint.length()); // SHA-256 hex string
        assertTrue(fingerprint.matches("[0-9a-f]+"));
    }

    @Test
    void testSSLContextCreation() throws Exception {
        // Given
        certificateManager.initialize();

        // When
        SSLContext sslContext = certificateManager.createSSLContext();

        // Then
        assertNotNull(sslContext);
        assertEquals("TLS", sslContext.getProtocol());
    }

    @Test
    void testCertificateValidation() throws Exception {
        // Given
        certificateManager.initialize();

        // When & Then
        assertTrue(certificateManager.isCertificateValid());
        
        X509Certificate cert = certificateManager.getClientCertificate();
        assertNotNull(cert);
        
        // Certificate should be valid for at least 360 days
        assertTrue(certificateManager.getDaysUntilExpiry() > 360);
    }

    @Test
    void testCertificateRotation() throws Exception {
        // Given
        certificateManager.initialize();
        String originalFingerprint = certificateManager.getCertificateFingerprint();

        // When
        certificateManager.rotateCertificate();

        // Then
        String newFingerprint = certificateManager.getCertificateFingerprint();
        assertNotEquals(originalFingerprint, newFingerprint);
        assertTrue(certificateManager.isCertificateValid());
    }

    @Test
    void testKeystorePersistence() throws Exception {
        // Given
        certificateManager.initialize();
        String originalFingerprint = certificateManager.getCertificateFingerprint();

        // When - create new instance and initialize (should load existing keystore)
        CertificateManager newManager = new CertificateManager();
        ReflectionTestUtils.setField(newManager, "keystorePath", 
                tempDir.resolve("test-keystore.p12").toString());
        ReflectionTestUtils.setField(newManager, "keystorePassword", "testpass");
        ReflectionTestUtils.setField(newManager, "keyAlias", "test-key");
        ReflectionTestUtils.setField(newManager, "clientId", "test-client");
        
        newManager.initialize();

        // Then
        assertEquals(originalFingerprint, newManager.getCertificateFingerprint());
    }
}