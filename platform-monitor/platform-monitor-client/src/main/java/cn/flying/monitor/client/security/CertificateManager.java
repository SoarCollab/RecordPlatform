package cn.flying.monitor.client.security;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Certificate management service for secure client authentication
 * Handles certificate generation, loading, validation, and rotation
 */
@Slf4j
@Component
public class CertificateManager {

    @Value("${monitor.client.certificate.keystore-path:./client-keystore.p12}")
    private String keystorePath;

    @Value("${monitor.client.certificate.keystore-password:changeit}")
    private String keystorePassword;

    @Value("${monitor.client.certificate.key-alias:client-key}")
    private String keyAlias;

    @Value("${monitor.client.certificate.validity-days:365}")
    private int validityDays;

    @Value("${monitor.client.certificate.auto-rotation-days:30}")
    private int autoRotationDays;

    @Value("${monitor.client.id}")
    private String clientId;

    private KeyStore keyStore;
    private X509Certificate clientCertificate;
    private PrivateKey privateKey;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Initialize certificate manager and load or generate certificates
     */
    public void initialize() throws Exception {
        log.info("Initializing certificate manager for client: {}", clientId);
        
        loadOrCreateKeyStore();
        
        if (certificateExists()) {
            loadExistingCertificate();
            if (shouldRotateCertificate()) {
                log.info("Certificate is nearing expiry, rotating...");
                rotateCertificate();
            }
        } else {
            log.info("No existing certificate found, generating new one...");
            generateNewCertificate();
        }
        
        log.info("Certificate manager initialized successfully");
    }

    /**
     * Load or create the keystore
     */
    private void loadOrCreateKeyStore() throws Exception {
        keyStore = KeyStore.getInstance("PKCS12");
        Path keystoreFile = Paths.get(keystorePath);
        
        if (Files.exists(keystoreFile)) {
            try (FileInputStream fis = new FileInputStream(keystoreFile.toFile())) {
                keyStore.load(fis, keystorePassword.toCharArray());
                log.info("Loaded existing keystore from: {}", keystorePath);
            }
        } else {
            keyStore.load(null, null);
            log.info("Created new keystore");
        }
    }

    /**
     * Check if certificate exists in keystore
     */
    private boolean certificateExists() {
        try {
            return keyStore.containsAlias(keyAlias);
        } catch (Exception e) {
            log.error("Error checking certificate existence", e);
            return false;
        }
    }

    /**
     * Load existing certificate and private key from keystore
     */
    private void loadExistingCertificate() throws Exception {
        clientCertificate = (X509Certificate) keyStore.getCertificate(keyAlias);
        privateKey = (PrivateKey) keyStore.getKey(keyAlias, keystorePassword.toCharArray());
        
        log.info("Loaded existing certificate. Subject: {}, Valid until: {}", 
                clientCertificate.getSubjectDN(), clientCertificate.getNotAfter());
    }

    /**
     * Check if certificate should be rotated based on expiry date
     */
    private boolean shouldRotateCertificate() {
        if (clientCertificate == null) {
            return true;
        }
        
        LocalDateTime expiryDate = clientCertificate.getNotAfter()
                .toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        LocalDateTime rotationThreshold = LocalDateTime.now().plusDays(autoRotationDays);
        
        return expiryDate.isBefore(rotationThreshold);
    }

    /**
     * Generate new certificate and private key
     */
    private void generateNewCertificate() throws Exception {
        // Generate key pair
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        
        privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        // Create certificate
        X500Name subject = new X500Name("CN=" + clientId + ", O=Monitor Client, C=US");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date();
        Date notAfter = Date.from(LocalDateTime.now().plusDays(validityDays)
                .atZone(ZoneId.systemDefault()).toInstant());

        SubjectPublicKeyInfo subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.getEncoded());
        X509v3CertificateBuilder certificateBuilder = new X509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, subjectPublicKeyInfo);

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider("BC").build(privateKey);
        X509CertificateHolder certificateHolder = certificateBuilder.build(signer);
        
        clientCertificate = new JcaX509CertificateConverter()
                .setProvider("BC").getCertificate(certificateHolder);

        // Store in keystore
        Certificate[] certificateChain = {clientCertificate};
        keyStore.setKeyEntry(keyAlias, privateKey, keystorePassword.toCharArray(), certificateChain);
        
        saveKeyStore();
        
        log.info("Generated new certificate for client: {}, Valid until: {}", 
                clientId, clientCertificate.getNotAfter());
    }

    /**
     * Rotate certificate by generating a new one
     */
    public void rotateCertificate() throws Exception {
        log.info("Rotating certificate for client: {}", clientId);
        generateNewCertificate();
        log.info("Certificate rotation completed");
    }

    /**
     * Save keystore to file
     */
    private void saveKeyStore() throws Exception {
        try (FileOutputStream fos = new FileOutputStream(keystorePath)) {
            keyStore.store(fos, keystorePassword.toCharArray());
        }
        log.debug("Keystore saved to: {}", keystorePath);
    }

    /**
     * Get the client certificate
     */
    public X509Certificate getClientCertificate() {
        return clientCertificate;
    }

    /**
     * Get the private key
     */
    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    /**
     * Get certificate fingerprint for identification
     */
    public String getCertificateFingerprint() throws Exception {
        if (clientCertificate == null) {
            throw new IllegalStateException("Certificate not initialized");
        }
        
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(clientCertificate.getEncoded());
        
        StringBuilder fingerprint = new StringBuilder();
        for (byte b : digest) {
            fingerprint.append(String.format("%02x", b));
        }
        
        return fingerprint.toString();
    }

    /**
     * Create SSL context with client certificate
     */
    public SSLContext createSSLContext() throws Exception {
        // Initialize key manager with client certificate
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, keystorePassword.toCharArray());

        // Initialize trust manager (for server certificate validation)
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((KeyStore) null); // Use default trust store

        // Create SSL context
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), new SecureRandom());

        return sslContext;
    }

    /**
     * Validate certificate is still valid
     */
    public boolean isCertificateValid() {
        if (clientCertificate == null) {
            return false;
        }
        
        try {
            clientCertificate.checkValidity();
            return true;
        } catch (Exception e) {
            log.warn("Certificate validation failed", e);
            return false;
        }
    }

    /**
     * Get days until certificate expiry
     */
    public long getDaysUntilExpiry() {
        if (clientCertificate == null) {
            return 0;
        }
        
        LocalDateTime expiryDate = clientCertificate.getNotAfter()
                .toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        LocalDateTime now = LocalDateTime.now();
        
        return java.time.Duration.between(now, expiryDate).toDays();
    }
}