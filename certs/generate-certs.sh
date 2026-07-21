#!/bin/bash
# =============================================================================
# generate-certs.sh
#
# Generates a complete mTLS certificate chain for local banking app testing.
#
# What gets created:
#   ca.crt / ca.key             — Root CA (signs all other certs)
#   server.crt / server.key     — Server cert for account/customer services
#   client.crt / client.key     — Client cert for payment-service
#   server.p12                  — Server keystore (account-service, customer-service)
#   client.p12                  — Client keystore (payment-service)
#   truststore.jks              — Shared truststore containing CA cert (all services)
#
# Password for all keystores: changeit
#
# How mTLS works here:
#   1. payment-service presents client.p12 when calling account/customer services
#   2. account/customer services verify the client cert against truststore.jks
#   3. payment-service verifies the server cert against truststore.jks
#   4. Both sides trust the same CA → handshake succeeds
#
# Usage:
#   chmod +x generate-certs.sh && ./generate-certs.sh
#   Then copy the generated files into each service's src/main/resources/certs/
# =============================================================================

set -e

PASS="changeit"
DAYS=3650

echo ""
echo "=========================================="
echo "  Banking mTLS Certificate Generator"
echo "=========================================="
echo ""

# ── Step 1: Root CA ────────────────────────────────────────────────────────────
echo "Step 1: Generating Root CA..."
openssl genrsa -out ca.key 4096

openssl req -x509 -new -nodes \
    -key ca.key \
    -sha256 \
    -days $DAYS \
    -subj "/C=GB/ST=England/L=London/O=Banking Root CA/OU=Security/CN=Banking Internal CA" \
    -out ca.crt

echo "  → ca.key and ca.crt created"

# ── Step 2: Server Certificate (account-service, customer-service) ─────────────
echo ""
echo "Step 2: Generating server certificate..."
openssl genrsa -out server.key 2048

openssl req -new \
    -key server.key \
    -subj "/C=GB/ST=England/L=London/O=Banking/OU=Backend/CN=localhost" \
    -out server.csr

# SAN extension for localhost + service names
cat > server-ext.cnf << EOF
[v3_req]
subjectAltName = @alt_names
[alt_names]
DNS.1 = localhost
DNS.2 = account-service
DNS.3 = customer-service
IP.1  = 127.0.0.1
EOF

openssl x509 -req \
    -in server.csr \
    -CA ca.crt \
    -CAkey ca.key \
    -CAcreateserial \
    -days $DAYS \
    -sha256 \
    -extfile server-ext.cnf \
    -extensions v3_req \
    -out server.crt

echo "  → server.key and server.crt created"

# ── Step 3: Client Certificate (payment-service) ───────────────────────────────
echo ""
echo "Step 3: Generating client certificate (payment-service)..."
openssl genrsa -out client.key 2048

openssl req -new \
    -key client.key \
    -subj "/C=GB/ST=England/L=London/O=Banking/OU=Payments/CN=payment-service" \
    -out client.csr

openssl x509 -req \
    -in client.csr \
    -CA ca.crt \
    -CAkey ca.key \
    -CAcreateserial \
    -days $DAYS \
    -sha256 \
    -out client.crt

echo "  → client.key and client.crt created"

# ── Step 4: PKCS12 Keystores ──────────────────────────────────────────────────
echo ""
echo "Step 4: Creating PKCS12 keystores..."

# Server keystore (used by account-service and customer-service)
openssl pkcs12 -export \
    -in server.crt \
    -inkey server.key \
    -certfile ca.crt \
    -name "server" \
    -out server.p12 \
    -passout pass:$PASS

# Client keystore (used by payment-service to present its cert)
openssl pkcs12 -export \
    -in client.crt \
    -inkey client.key \
    -certfile ca.crt \
    -name "payment-service" \
    -out client.p12 \
    -passout pass:$PASS

echo "  → server.p12 created (password: $PASS)"
echo "  → client.p12 created (password: $PASS)"

# ── Step 5: Java TrustStore ────────────────────────────────────────────────────
echo ""
echo "Step 5: Creating Java TrustStore..."

# Delete if exists (keytool won't overwrite)
rm -f truststore.jks

keytool -import \
    -file ca.crt \
    -alias banking-ca \
    -keystore truststore.jks \
    -storepass $PASS \
    -noprompt

echo "  → truststore.jks created (password: $PASS)"

# ── Step 6: Copy to service resource directories ───────────────────────────────
echo ""
echo "Step 6: Copying certs to service resource directories..."

copy_if_dir_exists() {
    local DIR=$1
    local FILE=$2
    if [ -d "$DIR" ]; then
        cp "$FILE" "$DIR/"
        echo "  → $FILE → $DIR/"
    fi
}

# Server keystores → account-service and customer-service
for SERVICE in account-service customer-service; do
    CERTS_DIR="../${SERVICE}/src/main/resources/certs"
    if [ -d "$CERTS_DIR" ]; then
        cp server.p12   "$CERTS_DIR/"
        cp truststore.jks "$CERTS_DIR/"
        echo "  → server.p12, truststore.jks → $CERTS_DIR/"
    fi
done

# Client keystore + truststore → payment-service
PAYMENT_CERTS="../payment-service/src/main/resources/certs"
if [ -d "$PAYMENT_CERTS" ]; then
    cp client.p12    "$PAYMENT_CERTS/"
    cp truststore.jks "$PAYMENT_CERTS/"
    echo "  → client.p12, truststore.jks → $PAYMENT_CERTS/"
fi

# ── Cleanup ────────────────────────────────────────────────────────────────────
rm -f server.csr client.csr server-ext.cnf ca.srl

echo ""
echo "=========================================="
echo "  Done! Certificate Summary:"
echo "=========================================="
echo ""
echo "  CA Certificate:       ca.crt"
echo "  Server Keystore:      server.p12    (password: $PASS)"
echo "  Client Keystore:      client.p12    (password: $PASS)"
echo "  TrustStore:           truststore.jks (password: $PASS)"
echo ""
echo "  ✅ server.p12 copied to account-service and customer-service"
echo "  ✅ client.p12 copied to payment-service"
echo "  ✅ truststore.jks copied to all services"
echo ""
echo "  Verify certs:"
echo "    openssl x509 -in ca.crt -text -noout | grep CN"
echo "    openssl x509 -in server.crt -text -noout | grep -E 'CN|DNS'"
echo "    openssl x509 -in client.crt -text -noout | grep CN"
echo ""
echo "  Verify mTLS chain:"
echo "    openssl verify -CAfile ca.crt server.crt"
echo "    openssl verify -CAfile ca.crt client.crt"
