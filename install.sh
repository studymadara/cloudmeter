#!/usr/bin/env bash
# CloudMeter sidecar — one-line installer
#
#   curl -fsSL https://raw.githubusercontent.com/your-org/cloudmeter/main/install.sh | bash
#
# What this does:
#   1. Detects your OS and CPU architecture
#   2. Downloads the correct pre-built binary from the latest GitHub Release
#   3. Installs it to ~/.local/bin (no sudo needed)
#   4. Prints a quick-start command
#
# Supported platforms:
#   Linux  x86_64 / arm64
#   macOS  x86_64 (Intel) / arm64 (Apple Silicon)
#   Windows — use WSL2 and run this script there, or download the .exe manually

set -euo pipefail

REPO="your-org/cloudmeter"
INSTALL_DIR="${HOME}/.local/bin"
BINARY_NAME="cloudmeter-sidecar"

# ── colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BOLD='\033[1m'; NC='\033[0m'
info()    { echo -e "${BOLD}[cloudmeter]${NC} $*"; }
success() { echo -e "${GREEN}[cloudmeter]${NC} $*"; }
warn()    { echo -e "${YELLOW}[cloudmeter]${NC} $*"; }
die()     { echo -e "${RED}[cloudmeter] ERROR:${NC} $*" >&2; exit 1; }

# ── detect OS ────────────────────────────────────────────────────────────────
OS="$(uname -s)"
case "${OS}" in
  Linux*)   OS=linux ;;
  Darwin*)  OS=macos ;;
  *)        die "Unsupported OS: ${OS}. On Windows, use WSL2 or download the .exe from the releases page." ;;
esac

# ── detect arch ──────────────────────────────────────────────────────────────
ARCH="$(uname -m)"
case "${ARCH}" in
  x86_64)           ARCH=x86_64 ;;
  amd64)            ARCH=x86_64 ;;
  aarch64|arm64)    ARCH=arm64  ;;
  *)                die "Unsupported architecture: ${ARCH}" ;;
esac

ASSET="${BINARY_NAME}-${OS}-${ARCH}"
info "Detected platform: ${OS}/${ARCH} → asset: ${ASSET}"

# ── resolve latest release tag ───────────────────────────────────────────────
info "Fetching latest release from github.com/${REPO}..."
if command -v curl &>/dev/null; then
  LATEST_JSON=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest")
else
  die "curl is required but not found. Please install curl."
fi

TAG=$(echo "${LATEST_JSON}" | python3 -c "import sys,json; print(json.load(sys.stdin)['tag_name'])" 2>/dev/null) \
  || die "Could not parse latest release tag. Is the repo public and does it have releases?"

DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${TAG}/${ASSET}"
info "Downloading ${ASSET} (${TAG})..."

# ── download ─────────────────────────────────────────────────────────────────
mkdir -p "${INSTALL_DIR}"
TMP_FILE=$(mktemp)
curl -fsSL --progress-bar "${DOWNLOAD_URL}" -o "${TMP_FILE}" \
  || die "Download failed. Check that ${DOWNLOAD_URL} exists."

# ── verify checksum if sha256sums.txt is available ───────────────────────────
CHECKSUMS_URL="https://github.com/${REPO}/releases/download/${TAG}/sha256sums.txt"
if curl -fsSL "${CHECKSUMS_URL}" -o /tmp/cloudmeter-sha256sums.txt 2>/dev/null; then
  EXPECTED=$(grep "${ASSET}" /tmp/cloudmeter-sha256sums.txt | awk '{print $1}')
  if [[ -n "${EXPECTED}" ]]; then
    ACTUAL=$(sha256sum "${TMP_FILE}" | awk '{print $1}')
    if [[ "${EXPECTED}" != "${ACTUAL}" ]]; then
      rm -f "${TMP_FILE}"
      die "Checksum mismatch! Expected ${EXPECTED}, got ${ACTUAL}. Aborting."
    fi
    success "Checksum verified: ${ACTUAL}"
  fi
fi

# ── install ──────────────────────────────────────────────────────────────────
chmod +x "${TMP_FILE}"
mv "${TMP_FILE}" "${INSTALL_DIR}/${BINARY_NAME}"
success "Installed → ${INSTALL_DIR}/${BINARY_NAME}"

# ── PATH hint ────────────────────────────────────────────────────────────────
if ! echo "${PATH}" | grep -q "${INSTALL_DIR}"; then
  warn "${INSTALL_DIR} is not in your PATH."
  warn "Add this to your ~/.bashrc or ~/.zshrc:"
  warn ""
  warn "  export PATH=\"\${HOME}/.local/bin:\${PATH}\""
  warn ""
fi

# ── done ─────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}CloudMeter sidecar ${TAG} installed.${NC}"
echo ""
echo "  Quick start:"
echo "    ${BINARY_NAME} --provider AWS --region us-east-1 --target-users 1000"
echo ""
echo "  Then open:  http://localhost:7777"
echo "  Ingest on:  http://127.0.0.1:7778/api/metrics"
echo ""
echo "  Python middleware:  https://github.com/${REPO}/tree/main/sidecar-rs#integrating-from-python"
echo "  Node.js middleware: https://github.com/${REPO}/tree/main/sidecar-rs#integrating-from-nodejs"
echo ""
