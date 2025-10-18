# Certificate Management Guide

This guide provides comprehensive instructions for managing X.509 certificates in the Monitor System, including client authentication, certificate lifecycle management, and security best practices.

## Table of Contents

1. [Certificate Overview](#certificate-overview)
2. [Certificate Authority Setup](#certificate-authority-setup)
3. [Client Certificate Generation](#client-certificate-generation)
4. [Certificate Registration](#certificate-registration)
5. [Certificate Validation](#certificate-validation)
6. [Certificate Revocation](#certificate-revocation)
7. [Certificate Renewal](#certificate-renewal)
8. [Security Best Practices](#security-best-practices)
9. [Troubleshooting](#troubleshooting)

## Certificate Overview

The Monitor System uses X.509 certificates for secure client authentication. This provides stronger security than password-based authentication and enables automated client registration.

### Certificate Hierarchy

```
Root CA Certificate
├── Intermediate CA Certificate (optional)
└── Client Certificates
    ├── client-001.crt
    ├── client-002.crt
    └── ...
```

### Certificate Components

- **Root CA**: Self-signed certificate that signs client certificates
- **Client Certificate**: Identifies individual monitoring clients
- **Private Key**: Used by clients for authentication
- **Certificate Store**: Redis-based storage for certificate validation

## Certificate Authority Setup

### 1. Generate Root CA Private Key

```bash
# Generate RSA private key for CA
openssl genrsa -aes256 -out ca-private-key.pem 4096

# Or generate ECDSA private key (recommended for better performance)
openssl ecparam -genkey -name secp384r1 -out ca-private-key.pem
```

### 2. Create Root CA Certificate

```bash
# Create CA certificate configuration
cat > ca.conf << EOF
[req]
distinguished_name = req_distinguished_name
x509_extensions = v3_ca
prompt = no

[req_distinguished_name]
C = US
ST = California
L = San Francisco
O = Monitor System
OU = Certificate Authority
CN = Monitor System Root CA
emailAddress = ca@monitor.com

[v3_ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer:always
EOF

# Generate CA certificate
openssl req -new -x509 -days 3650 -key ca-private-key.pem \
  -out ca-certificate.pem -config ca.conf
```

### 3. Verify CA Certificate

```bash
# Display certificate information
openssl x509 -in ca-certificate.pem -text -noout

# Verify certificate
openssl x509 -in ca-certificate.pem -noout -verify
```

## Client Certificate Generation

### 1. Generate Client Private Key

```bash
# Generate client private key
openssl genrsa -out client-001-private-key.pem 2048

# Or use ECDSA (recommended)
openssl ecparam -genkey -name prime256v1 -out client-001-private-key.pem
```

### 2. Create Certificate Signing Request (CSR)

```bash
# Create client certificate configuration
cat > client-001.conf << EOF
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no

[req_distinguished_name]
C = US
ST = California
L = San Francisco
O = Monitor System
OU = Monitoring Clients
CN = client-001
emailAddress = client-001@monitor.com

[v3_req]
basicConstraints = CA:FALSE
keyUsage = nonRepudiation,digitalSignature,keyEncipherment
subjectAltName = @alt_names
extendedKeyUsage = clientAuth

[alt_names]
DNS.1 = client-001
DNS.2 = client-001.monitor.local
IP.1 = 192.168.1.100
EOF

# Generate CSR
openssl req -new -key client-001-private-key.pem \
  -out client-001.csr -config client-001.conf
```

### 3. Sign Client Certificate

```bash
# Sign the CSR with CA certificate
openssl x509 -req -in client-001.csr \
  -CA ca-certificate.pem \
  -CAkey ca-private-key.pem \
  -CAcreateserial \
  -out client-001-certificate.pem \
  -days 365 \
  -extensions v3_req \
  -extfile client-001.conf
```

### 4. Create Certificate Bundle

```bash
# Combine certificate and CA for client
cat client-001-certificate.pem ca-certificate.pem > client-001-bundle.pem

# Create PKCS#12 bundle (optional)
openssl pkcs12 -export -out client-001.p12 \
  -inkey client-001-private-key.pem \
  -in client-001-certificate.pem \
  -certfile ca-certificate.pem \
  -name "client-001"
```

### 5. Verify Client Certificate

```bash
# Verify certificate against CA
openssl verify -CAfile ca-certificate.pem client-001-certificate.pem

# Check certificate details
openssl x509 -in client-001-certificate.pem -text -noout
```

## Certificate Registration

### 1. Register Certificate via API

```bash
# Register client certificate
curl -X POST "https://monitor.yourdomain.com/api/certificates/register" \
  -H "Authorization: Bearer <admin-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "client-001",
    "certificatePem": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
    "description": "Production web server monitoring client"
  }'
```

### 2. Register Certificate via CLI Tool

```bash
# Using monitor CLI tool
monitor-cli certificate register \
  --client-id client-001 \
  --certificate-file client-001-certificate.pem \
  --description "Production web server monitoring client"
```

### 3. Bulk Certificate Registration

```bash
# Register multiple certificates
cat > certificates.json << EOF
{
  "certificates": [
    {
      "clientId": "client-001",
      "certificateFile": "client-001-certificate.pem",
      "description": "Web server 1"
    },
    {
      "clientId": "client-002",
      "certificateFile": "client-002-certificate.pem",
      "description": "Web server 2"
    }
  ]
}
EOF

# Bulk register
curl -X POST "https://monitor.yourdomain.com/api/certificates/bulk-register" \
  -H "Authorization: Bearer <admin-jwt-token>" \
  -H "Content-Type: application/json" \
  -d @certificates.json
```

## Certificate Validation

### 1. Validation Process

The system validates certificates through multiple checks:

1. **Certificate Format**: Valid X.509 format
2. **CA Signature**: Signed by trusted CA
3. **Expiration**: Not expired
4. **Revocation**: Not in revocation list
5. **Client ID**: Matches registered client

### 2. Validation Configuration

```yaml
# application.yml
monitor:
  security:
    certificate:
      enabled: true
      validation:
        check-expiry: true
        check-revocation: true
        require-client-cert: true
        trusted-ca-certs: classpath:ca-certificates/
        crl-urls:
          - http://crl.monitor.com/ca.crl
        ocsp-urls:
          - http://ocsp.monitor.com
```

### 3. Manual Certificate Validation

```bash
# Validate certificate via API
curl -X POST "https://monitor.yourdomain.com/api/certificates/validate" \
  -H "Authorization: Bearer <admin-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "client-001",
    "certificatePem": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----"
  }'
```

### 4. Certificate Validation Logs

```bash
# Check validation logs
kubectl logs deployment/monitor-auth-service -n monitor-system | grep "certificate.validation"

# Monitor validation metrics
curl http://auth-service:8081/actuator/metrics/certificate.validation.success
curl http://auth-service:8081/actuator/metrics/certificate.validation.failures
```

## Certificate Revocation

### 1. Revoke Certificate via API

```bash
# Revoke certificate
curl -X POST "https://monitor.yourdomain.com/api/certificates/revoke" \
  -H "Authorization: Bearer <admin-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "client-001",
    "reason": "COMPROMISED",
    "description": "Private key potentially compromised"
  }'
```

### 2. Certificate Revocation List (CRL)

```bash
# Generate CRL
openssl ca -gencrl -out ca.crl \
  -config ca.conf \
  -cert ca-certificate.pem \
  -keyfile ca-private-key.pem

# Add certificate to CRL
openssl ca -revoke client-001-certificate.pem \
  -config ca.conf \
  -cert ca-certificate.pem \
  -keyfile ca-private-key.pem

# Regenerate CRL
openssl ca -gencrl -out ca.crl \
  -config ca.conf \
  -cert ca-certificate.pem \
  -keyfile ca-private-key.pem
```

### 3. OCSP Responder Setup

```bash
# Start OCSP responder
openssl ocsp -port 8888 -text \
  -CA ca-certificate.pem \
  -index index.txt \
  -rkey ca-private-key.pem \
  -rsigner ca-certificate.pem
```

### 4. Blacklist Management

```bash
# Add certificate to blacklist
curl -X POST "https://monitor.yourdomain.com/api/certificates/blacklist" \
  -H "Authorization: Bearer <admin-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "serialNumber": "01:23:45:67:89:AB:CD:EF",
    "reason": "COMPROMISED"
  }'

# Remove from blacklist
curl -X DELETE "https://monitor.yourdomain.com/api/certificates/blacklist/01:23:45:67:89:AB:CD:EF" \
  -H "Authorization: Bearer <admin-jwt-token>"
```

## Certificate Renewal

### 1. Automated Renewal Script

```bash
#!/bin/bash
# certificate-renewal.sh

CLIENT_ID="client-001"
CERT_DIR="/etc/monitor/certificates"
CA_CERT="$CERT_DIR/ca-certificate.pem"
CA_KEY="$CERT_DIR/ca-private-key.pem"
CLIENT_KEY="$CERT_DIR/$CLIENT_ID-private-key.pem"
CLIENT_CERT="$CERT_DIR/$CLIENT_ID-certificate.pem"

# Check certificate expiration
EXPIRY_DATE=$(openssl x509 -in $CLIENT_CERT -noout -enddate | cut -d= -f2)
EXPIRY_TIMESTAMP=$(date -d "$EXPIRY_DATE" +%s)
CURRENT_TIMESTAMP=$(date +%s)
DAYS_UNTIL_EXPIRY=$(( ($EXPIRY_TIMESTAMP - $CURRENT_TIMESTAMP) / 86400 ))

if [ $DAYS_UNTIL_EXPIRY -lt 30 ]; then
    echo "Certificate expires in $DAYS_UNTIL_EXPIRY days. Renewing..."
    
    # Generate new CSR
    openssl req -new -key $CLIENT_KEY -out $CLIENT_ID.csr -config $CLIENT_ID.conf
    
    # Sign new certificate
    openssl x509 -req -in $CLIENT_ID.csr \
      -CA $CA_CERT \
      -CAkey $CA_KEY \
      -CAcreateserial \
      -out $CLIENT_ID-new-certificate.pem \
      -days 365 \
      -extensions v3_req \
      -extfile $CLIENT_ID.conf
    
    # Register new certificate
    curl -X POST "https://monitor.yourdomain.com/api/certificates/renew" \
      -H "Authorization: Bearer $CLIENT_TOKEN" \
      -H "Content-Type: application/json" \
      -d "{
        \"clientId\": \"$CLIENT_ID\",
        \"certificatePem\": \"$(cat $CLIENT_ID-new-certificate.pem | sed ':a;N;$!ba;s/\n/\\n/g')\"
      }"
    
    # Replace old certificate
    mv $CLIENT_ID-new-certificate.pem $CLIENT_CERT
    
    echo "Certificate renewed successfully"
else
    echo "Certificate is valid for $DAYS_UNTIL_EXPIRY more days"
fi
```

### 2. Renewal via API

```bash
# Renew certificate
curl -X POST "https://monitor.yourdomain.com/api/certificates/renew" \
  -H "Authorization: Bearer <client-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "client-001",
    "certificatePem": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----"
  }'
```

### 3. Automated Renewal with Cron

```bash
# Add to crontab
crontab -e

# Check for renewal daily at 2 AM
0 2 * * * /usr/local/bin/certificate-renewal.sh >> /var/log/cert-renewal.log 2>&1
```

## Security Best Practices

### 1. Private Key Security

- **Storage**: Store private keys in secure locations with restricted access
- **Permissions**: Set file permissions to 600 (owner read/write only)
- **Encryption**: Encrypt private keys with strong passphrases
- **Backup**: Securely backup private keys with encryption

```bash
# Secure private key permissions
chmod 600 client-001-private-key.pem
chown monitor:monitor client-001-private-key.pem

# Encrypt private key
openssl rsa -in client-001-private-key.pem -aes256 -out client-001-private-key-encrypted.pem
```

### 2. Certificate Lifecycle Management

- **Expiration Monitoring**: Monitor certificate expiration dates
- **Automated Renewal**: Implement automated renewal processes
- **Revocation Checking**: Regularly check for revoked certificates
- **Audit Logging**: Log all certificate operations

### 3. CA Security

- **Offline CA**: Keep root CA offline when possible
- **Hardware Security Modules (HSM)**: Use HSM for CA key storage
- **Access Control**: Restrict CA access to authorized personnel
- **Regular Audits**: Conduct regular security audits

### 4. Network Security

- **TLS Encryption**: Always use TLS for certificate transmission
- **Certificate Pinning**: Implement certificate pinning where possible
- **Mutual TLS**: Use mutual TLS authentication
- **Network Segmentation**: Isolate certificate infrastructure

## Troubleshooting

### Common Certificate Issues

#### 1. Certificate Validation Failures

**Symptoms:**
- "Certificate validation failed" errors
- Authentication failures
- Connection refused

**Diagnosis:**
```bash
# Check certificate validity
openssl x509 -in client-certificate.pem -noout -dates

# Verify certificate chain
openssl verify -CAfile ca-certificate.pem client-certificate.pem

# Check certificate details
openssl x509 -in client-certificate.pem -text -noout
```

**Solutions:**
- Ensure certificate is not expired
- Verify certificate is signed by trusted CA
- Check certificate is not revoked
- Validate certificate format

#### 2. Certificate Store Issues

**Symptoms:**
- "Certificate not found" errors
- Inconsistent validation results
- Performance issues

**Diagnosis:**
```bash
# Check Redis certificate storage
kubectl exec -it redis-pod -n monitor-system -- redis-cli keys "cert:*"

# Check certificate data
kubectl exec -it redis-pod -n monitor-system -- redis-cli hgetall "cert:client-001"
```

**Solutions:**
- Verify Redis connectivity
- Check certificate registration
- Clear and re-register certificates
- Monitor Redis memory usage

#### 3. Certificate Expiration

**Symptoms:**
- Sudden authentication failures
- "Certificate expired" errors
- Client disconnections

**Diagnosis:**
```bash
# Check certificate expiration
openssl x509 -in client-certificate.pem -noout -enddate

# List expiring certificates
curl -X GET "https://monitor.yourdomain.com/api/certificates/expiring?days=30" \
  -H "Authorization: Bearer <admin-jwt-token>"
```

**Solutions:**
- Implement expiration monitoring
- Set up automated renewal
- Maintain certificate inventory
- Configure expiration alerts

#### 4. CA Certificate Issues

**Symptoms:**
- All certificate validations fail
- "Unknown CA" errors
- Trust chain failures

**Diagnosis:**
```bash
# Verify CA certificate
openssl x509 -in ca-certificate.pem -noout -verify

# Check CA certificate in trust store
keytool -list -keystore cacerts -alias monitor-ca
```

**Solutions:**
- Verify CA certificate validity
- Update trust stores
- Check CA certificate distribution
- Validate CA configuration

### Certificate Monitoring

#### 1. Expiration Monitoring

```bash
# Monitor certificate expiration
curl http://auth-service:8081/actuator/metrics/certificate.expiration.days

# Set up alerts for expiring certificates
curl -X POST "https://monitor.yourdomain.com/api/alerts/rules" \
  -H "Authorization: Bearer <admin-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Certificate Expiration Alert",
    "condition": "certificate.expiration.days < 30",
    "severity": "WARNING",
    "notification": {
      "email": ["admin@monitor.com"],
      "webhook": "https://alerts.monitor.com/webhook"
    }
  }'
```

#### 2. Validation Metrics

```bash
# Monitor validation success rate
curl http://auth-service:8081/actuator/metrics/certificate.validation.success.rate

# Monitor validation failures
curl http://auth-service:8081/actuator/metrics/certificate.validation.failures
```

#### 3. Certificate Inventory

```bash
# List all registered certificates
curl -X GET "https://monitor.yourdomain.com/api/certificates" \
  -H "Authorization: Bearer <admin-jwt-token>"

# Export certificate inventory
curl -X GET "https://monitor.yourdomain.com/api/certificates/export" \
  -H "Authorization: Bearer <admin-jwt-token>" \
  -o certificate-inventory.csv
```

---

For additional information, see:
- [API Documentation](API.md)
- [Security Guide](SECURITY.md)
- [Deployment Guide](DEPLOYMENT.md)