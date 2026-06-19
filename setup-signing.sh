#!/bin/bash
# =============================================================================
# setup-signing.sh
# Run this ONCE locally to generate your APK signing keystore and print the
# values you need to paste into GitHub Secrets.
#
# Prerequisites: Java (keytool) must be installed and on your PATH.
#   - Mac/Linux: it comes with any JDK
#   - Windows:   run this from Git Bash or WSL, or use gradlew.bat equivalent
#
# Usage:
#   chmod +x setup-signing.sh
#   ./setup-signing.sh
# =============================================================================

set -e

# ---------- Configuration — edit these if you want ----------
KEYSTORE_FILE="keystore.jks"
KEY_ALIAS="plugin-key"
VALIDITY_DAYS=10000   # ~27 years
# ------------------------------------------------------------

echo ""
echo "============================================="
echo "  Dokuen Plugin — APK Signing Setup"
echo "============================================="
echo ""

# Prompt for passwords (hidden input)
read -s -p "Choose a STORE password (min 6 chars): " STORE_PASSWORD
echo ""
read -s -p "Confirm STORE password: " STORE_PASSWORD2
echo ""

if [ "$STORE_PASSWORD" != "$STORE_PASSWORD2" ]; then
  echo "❌ Passwords do not match. Please run the script again."
  exit 1
fi

read -s -p "Choose a KEY password (min 6 chars, can be the same): " KEY_PASSWORD
echo ""
read -s -p "Confirm KEY password: " KEY_PASSWORD2
echo ""

if [ "$KEY_PASSWORD" != "$KEY_PASSWORD2" ]; then
  echo "❌ Passwords do not match. Please run the script again."
  exit 1
fi

echo ""
echo "ℹ️  Generating keystore... (you can fill in the fields below or press Enter to skip each)"
echo ""

# Generate the keystore using keytool
keytool -genkeypair \
  -keystore "$KEYSTORE_FILE" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity "$VALIDITY_DAYS" \
  -storepass "$STORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -dname "CN=Plugin Developer, OU=Android, O=Personal, L=Unknown, S=Unknown, C=BR"

echo ""
echo "✅ Keystore generated: $KEYSTORE_FILE"
echo ""

# Encode the keystore as base64
KEYSTORE_BASE64=$(base64 -w 0 "$KEYSTORE_FILE" 2>/dev/null || base64 "$KEYSTORE_FILE")

# =============================================================================
echo ""
echo "============================================="
echo "  Add these 4 Secrets to your GitHub repo:"
echo "  Repo → Settings → Secrets and variables"
echo "          → Actions → New repository secret"
echo "============================================="
echo ""
echo "Secret name:  KEYSTORE_BASE64"
echo "Secret value: $KEYSTORE_BASE64"
echo ""
echo "---"
echo ""
echo "Secret name:  STORE_PASSWORD"
echo "Secret value: $STORE_PASSWORD"
echo ""
echo "---"
echo ""
echo "Secret name:  KEY_ALIAS"
echo "Secret value: $KEY_ALIAS"
echo ""
echo "---"
echo ""
echo "Secret name:  KEY_PASSWORD"
echo "Secret value: $KEY_PASSWORD"
echo ""
echo "============================================="
echo ""
echo "⚠️  IMPORTANT — do these now:"
echo ""
echo "  1. Copy the 4 secrets above into GitHub (link below)."
echo "     Your repo → Settings → Secrets and variables → Actions"
echo ""
echo "  2. Add keystore.jks to your .gitignore (NEVER commit it):"
echo "     echo 'keystore.jks' >> .gitignore"
echo ""
echo "  3. To publish a release, push a version tag:"
echo "     git tag v1.0.0"
echo "     git push origin v1.0.0"
echo "     GitHub Actions will build and publish the APK automatically."
echo ""
echo "  4. Delete or clear this terminal session when done so the"
echo "     passwords don't stay in your shell history."
echo ""