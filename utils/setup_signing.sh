#!/usr/bin/env bash
#
# setup_signing.sh - Generate (or reuse) an Android app signing keystore for FlorisBoard, wire it up locally via
# keystore.properties, and upload the same secrets to this repository's GitHub Actions secrets, so both local
# builds (debug/beta/release) and CI builds are signed with the same key.
#
# Usage:
#   utils/setup_signing.sh [options]
#
# Options:
#   --keystore-path <path>   Path (relative to repo root) to the keystore file.        [default: keystore/release.jks]
#   --alias <name>            Key alias inside the keystore.                            [default: florisboard]
#   --validity-years <n>      Certificate validity in years (only used when generating). [default: 30]
#   --dname <dname>           Certificate distinguished name (only used when generating).
#                              [default: "CN=FlorisBoard,OU=Dev,O=FlorisBoard,L=Unknown,ST=Unknown,C=US"]
#   --store-password <pass>   Keystore password. Auto-generated if omitted and creating fresh.
#   --key-password <pass>     Key password. Auto-generated if omitted and creating fresh.
#   --repo <owner/name>       GitHub repository to upload secrets to. Auto-detected from git remote if omitted.
#   --force-new                Regenerate the keystore even if one already exists at --keystore-path.
#                              DANGEROUS: see the warning below. Requires typing "yes" unless --yes is also given.
#   --yes                     Assume "yes" to the --force-new confirmation prompt (for non-interactive use).
#   --skip-github              Only do the local part (generate keystore + keystore.properties), do not touch
#                              GitHub secrets at all.
#   -h, --help                  Show this help and exit.
#
# What this script does NOT do:
#   - It never commits the keystore or its passwords to git (keystore.properties and *.jks are gitignored).
#   - It never overwrites an existing keystore without --force-new (losing a release keystore means you can
#     never publish an update to an app already installed from it under the same package/signature again).
#
# Requires: keytool (bundled with any JDK), openssl, git, and (unless --skip-github) the GitHub CLI (`gh`,
# already authenticated with `gh auth login` and with permission to manage secrets on the target repo).

set -euo pipefail

# ---------------------------------------------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------------------------------------------
KEYSTORE_REL_PATH="keystore/release.jks"
KEY_ALIAS="florisboard"
VALIDITY_YEARS=30
DNAME="CN=FlorisBoard,OU=Dev,O=FlorisBoard,L=Unknown,ST=Unknown,C=US"
STORE_PASSWORD=""
KEY_PASSWORD=""
REPO=""
FORCE_NEW=0
ASSUME_YES=0
SKIP_GITHUB=0

SECRET_KEYSTORE_BASE64="SIGNING_KEYSTORE_BASE64"
SECRET_STORE_PASSWORD="SIGNING_STORE_PASSWORD"
SECRET_KEY_ALIAS="SIGNING_KEY_ALIAS"
SECRET_KEY_PASSWORD="SIGNING_KEY_PASSWORD"

# ---------------------------------------------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------------------------------------------
log()  { printf '\033[1;34m[setup_signing]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[setup_signing]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[setup_signing]\033[0m %s\n' "$*" >&2; exit 1; }

print_help() {
    # Print this script's own header comment (everything between the shebang and the first blank-then-code line).
    sed -n '2,/^set -euo pipefail/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
}

read_property() {
    # read_property <file> <key> - tiny, dependency-free ".properties" reader (key=value per line, no shell eval).
    local file="$1" key="$2"
    [[ -f "$file" ]] || return 0
    grep -E "^${key}=" "$file" | head -n1 | cut -d'=' -f2-
}

random_password() {
    openssl rand -hex 24
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "Required command '$1' not found in PATH. $2"
}

# ---------------------------------------------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --keystore-path)   KEYSTORE_REL_PATH="$2"; shift 2 ;;
        --alias)           KEY_ALIAS="$2"; shift 2 ;;
        --validity-years)  VALIDITY_YEARS="$2"; shift 2 ;;
        --dname)           DNAME="$2"; shift 2 ;;
        --store-password)  STORE_PASSWORD="$2"; shift 2 ;;
        --key-password)    KEY_PASSWORD="$2"; shift 2 ;;
        --repo)            REPO="$2"; shift 2 ;;
        --force-new)       FORCE_NEW=1; shift ;;
        --yes)             ASSUME_YES=1; shift ;;
        --skip-github)     SKIP_GITHUB=1; shift ;;
        -h|--help)         print_help; exit 0 ;;
        *) die "Unknown option: $1 (use --help for usage)" ;;
    esac
done

# ---------------------------------------------------------------------------------------------------------------
# Environment checks
# ---------------------------------------------------------------------------------------------------------------
require_cmd git "Install git."
require_cmd keytool "Install a JDK (keytool ships with it) and make sure it's on PATH."
require_cmd openssl "Install openssl."

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || die "Not inside a git repository."
cd "$REPO_ROOT"

KEYSTORE_PROPERTIES_FILE="$REPO_ROOT/keystore.properties"
KEYSTORE_FILE="$REPO_ROOT/$KEYSTORE_REL_PATH"

if [[ $SKIP_GITHUB -eq 0 ]]; then
    require_cmd gh "Install the GitHub CLI (https://cli.github.com), or re-run with --skip-github to only set up local signing."
    gh auth status >/dev/null 2>&1 || die "GitHub CLI is not authenticated. Run 'gh auth login' first, or re-run with --skip-github."
    if [[ -z "$REPO" ]]; then
        REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)" \
            || die "Could not auto-detect the GitHub repository. Pass --repo <owner/name> explicitly."
    fi
fi

# ---------------------------------------------------------------------------------------------------------------
# Decide: reuse existing setup, generate fresh, or refuse (safety-first)
# ---------------------------------------------------------------------------------------------------------------
KEYSTORE_EXISTS=0
if [[ -f "$KEYSTORE_FILE" ]]; then KEYSTORE_EXISTS=1; fi
PROPERTIES_EXIST=0
if [[ -f "$KEYSTORE_PROPERTIES_FILE" ]]; then PROPERTIES_EXIST=1; fi

if [[ $KEYSTORE_EXISTS -eq 1 && $FORCE_NEW -eq 0 ]]; then
    if [[ $PROPERTIES_EXIST -eq 1 ]]; then
        log "Found an existing keystore and keystore.properties, reusing them (pass --force-new to replace)."
        KEYSTORE_REL_PATH="$(read_property "$KEYSTORE_PROPERTIES_FILE" storeFile)"
        [[ -n "$KEYSTORE_REL_PATH" ]] || die "keystore.properties exists but has no 'storeFile' entry, aborting."
        KEYSTORE_FILE="$REPO_ROOT/$KEYSTORE_REL_PATH"
        STORE_PASSWORD="$(read_property "$KEYSTORE_PROPERTIES_FILE" storePassword)"
        KEY_ALIAS="$(read_property "$KEYSTORE_PROPERTIES_FILE" keyAlias)"
        KEY_PASSWORD="$(read_property "$KEYSTORE_PROPERTIES_FILE" keyPassword)"
        [[ -n "$STORE_PASSWORD" && -n "$KEY_ALIAS" && -n "$KEY_PASSWORD" ]] \
            || die "keystore.properties is missing one of storePassword/keyAlias/keyPassword, aborting."
        GENERATE=0
    else
        die "A keystore already exists at '$KEYSTORE_REL_PATH' but there is no keystore.properties with its
password next to it, so this script cannot safely read or reuse it. Either:
  - point --keystore-path elsewhere to generate a new one, or
  - supply --store-password/--key-password/--alias matching the existing file, or
  - pass --force-new to replace it (DESTRUCTIVE, see --help)."
    fi
elif [[ $KEYSTORE_EXISTS -eq 1 && $FORCE_NEW -eq 1 ]]; then
    warn "############################################################################"
    warn "# --force-new was given: '$KEYSTORE_REL_PATH' will be REPLACED.            #"
    warn "# If this keystore was ever used to sign a build you installed or shipped, #"
    warn "# you will not be able to install updates over it or publish updates to    #"
    warn "# the same app listing again once this key is gone. Make sure you have a   #"
    warn "# backup if you need one before continuing.                                #"
    warn "############################################################################"
    if [[ $ASSUME_YES -eq 0 ]]; then
        read -r -p "Type 'yes' to permanently replace the existing keystore: " confirm
        [[ "$confirm" == "yes" ]] || die "Aborted, existing keystore left untouched."
    fi
    rm -f "$KEYSTORE_FILE"
    GENERATE=1
else
    GENERATE=1
fi

# ---------------------------------------------------------------------------------------------------------------
# Generate a fresh keystore (only when needed)
# ---------------------------------------------------------------------------------------------------------------
if [[ "${GENERATE:-0}" -eq 1 ]]; then
    [[ -n "$STORE_PASSWORD" ]] || STORE_PASSWORD="$(random_password)"
    if [[ -z "$KEY_PASSWORD" ]]; then
        KEY_PASSWORD="$STORE_PASSWORD"
    elif [[ "$KEY_PASSWORD" != "$STORE_PASSWORD" ]]; then
        # keytool defaults to the PKCS12 keystore format, which (unlike the legacy JKS format) does not support a
        # key password different from the store password - it silently ignores -keypass and reuses -storepass.
        # Match that here so keystore.properties actually reflects the password Gradle will need to use.
        warn "keytool's default keystore format (PKCS12) does not support a key password different from the"
        warn "store password; --key-password will be ignored and the store password used for both."
        KEY_PASSWORD="$STORE_PASSWORD"
    fi

    mkdir -p "$(dirname "$KEYSTORE_FILE")"
    log "Generating a new keystore at '$KEYSTORE_REL_PATH' (alias '$KEY_ALIAS', valid ${VALIDITY_YEARS}y)..."
    keytool -genkeypair -v \
        -keystore "$KEYSTORE_FILE" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 \
        -validity "$((VALIDITY_YEARS * 365))" \
        -storepass "$STORE_PASSWORD" \
        -keypass "$KEY_PASSWORD" \
        -dname "$DNAME" \
        -noprompt >/dev/null
    chmod 600 "$KEYSTORE_FILE"

    cat > "$KEYSTORE_PROPERTIES_FILE" <<EOF
# Generated by utils/setup_signing.sh - DO NOT COMMIT (already gitignored).
storeFile=$KEYSTORE_REL_PATH
storePassword=$STORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASSWORD
EOF
    chmod 600 "$KEYSTORE_PROPERTIES_FILE"
    log "Wrote local keystore.properties (gitignored)."
fi

# ---------------------------------------------------------------------------------------------------------------
# Make sure the sensitive files can never be committed, even on a fresh clone that predates this script
# ---------------------------------------------------------------------------------------------------------------
GITIGNORE_FILE="$REPO_ROOT/.gitignore"
for pattern in "keystore.properties" "keystore/"; do
    if [[ -f "$GITIGNORE_FILE" ]] && ! grep -qxF "$pattern" "$GITIGNORE_FILE"; then
        printf '%s\n' "$pattern" >> "$GITIGNORE_FILE"
        log "Added '$pattern' to .gitignore."
    fi
done

# ---------------------------------------------------------------------------------------------------------------
# Upload the same secrets to GitHub Actions, so CI builds sign with the identical key
# ---------------------------------------------------------------------------------------------------------------
if [[ $SKIP_GITHUB -eq 0 ]]; then
    log "Uploading signing secrets to GitHub repo '$REPO'..."
    KEYSTORE_B64_FILE="$(mktemp)"
    trap 'rm -f "$KEYSTORE_B64_FILE"' EXIT
    # openssl's base64 (unlike GNU/BSD `base64`) takes the same flags everywhere; -A disables line wrapping.
    openssl base64 -A -in "$KEYSTORE_FILE" -out "$KEYSTORE_B64_FILE"
    gh secret set "$SECRET_KEYSTORE_BASE64" --repo "$REPO" < "$KEYSTORE_B64_FILE"
    rm -f "$KEYSTORE_B64_FILE"
    trap - EXIT
    printf '%s' "$STORE_PASSWORD" | gh secret set "$SECRET_STORE_PASSWORD" --repo "$REPO"
    printf '%s' "$KEY_ALIAS"      | gh secret set "$SECRET_KEY_ALIAS" --repo "$REPO"
    printf '%s' "$KEY_PASSWORD"   | gh secret set "$SECRET_KEY_PASSWORD" --repo "$REPO"
    log "Uploaded: $SECRET_KEYSTORE_BASE64, $SECRET_STORE_PASSWORD, $SECRET_KEY_ALIAS, $SECRET_KEY_PASSWORD."
    log "The 'FlorisBoard CI' workflow reads these automatically (see .github/workflows/android.yml)."
else
    log "Skipped GitHub secrets upload (--skip-github)."
fi

# ---------------------------------------------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------------------------------------------
log "Done."
log "  Keystore:            $KEYSTORE_REL_PATH (gitignored, keep a secure backup of this file yourself)"
log "  keystore.properties: $(realpath --relative-to="$REPO_ROOT" "$KEYSTORE_PROPERTIES_FILE") (gitignored)"
log "Local Gradle builds (debug/beta/release) will now be signed with this key automatically."
if [[ $SKIP_GITHUB -eq 0 ]]; then
    log "  GitHub repo:        $REPO"
fi
warn "IMPORTANT: back up '$KEYSTORE_REL_PATH' somewhere safe outside this repo (password manager, encrypted"
warn "drive, etc). If you lose it, you can never publish an update to an app already installed/shipped under"
warn "this same signature again."
