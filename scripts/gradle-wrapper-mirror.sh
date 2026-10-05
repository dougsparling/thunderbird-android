#!/usr/bin/env bash
#
# Pre-seeds the Gradle wrapper cache with the distribution named in gradle/wrapper/gradle-wrapper.properties,
# downloaded from a mainland-China mirror instead of services.gradle.org.
#
# The committed distributionUrl is left untouched. The wrapper finds the zip in its usual location, verifies it
# against distributionSha256Sum, unpacks it and never contacts services.gradle.org.
#
# Usage: scripts/gradle-wrapper-mirror.sh
#
# See docs/developer/china-mirrors.md

set -euo pipefail

MIRRORS=(
  "https://mirrors.cloud.tencent.com/gradle/"
  "https://mirrors.huaweicloud.com/gradle/"
)

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
properties_file="$project_dir/gradle/wrapper/gradle-wrapper.properties"

die() {
  echo "error: $*" >&2
  exit 1
}

# Reads a value from gradle-wrapper.properties and removes Java properties escaping (e.g. "https\://").
read_property() {
  local value
  value="$(grep -E "^[[:space:]]*$1[[:space:]]*=" "$properties_file" | tail -n 1 | cut -d '=' -f 2- || true)"
  value="${value%$'\r'}"
  value="${value//\\:/:}"
  value="${value//\\=/=}"
  printf '%s' "$value"
}

md5_hex() {
  if command -v md5sum > /dev/null; then
    printf '%s' "$1" | md5sum | cut -d ' ' -f 1
  elif command -v md5 > /dev/null; then
    md5 -q -s "$1"
  else
    die "md5sum or md5 is required"
  fi
}

sha256_file() {
  if command -v sha256sum > /dev/null; then
    sha256sum "$1" | cut -d ' ' -f 1
  elif command -v shasum > /dev/null; then
    shasum -a 256 "$1" | cut -d ' ' -f 1
  else
    die "sha256sum or shasum is required"
  fi
}

# Same as the wrapper's PathAssembler: MD5 of the distribution URL, as an unsigned integer in base 36.
wrapper_url_hash() {
  command -v bc > /dev/null || die "bc is required"

  local digits="0123456789abcdefghijklmnopqrstuvwxyz"
  local hex decimal remainder result=""
  hex="$(md5_hex "$1" | tr '[:lower:]' '[:upper:]')"
  decimal="$(echo "ibase=16; $hex" | BC_LINE_LENGTH=0 bc)"

  while [ "$decimal" != "0" ]; do
    remainder="$(echo "$decimal % 36" | BC_LINE_LENGTH=0 bc)"
    decimal="$(echo "$decimal / 36" | BC_LINE_LENGTH=0 bc)"
    result="${digits:$remainder:1}$result"
  done

  printf '%s' "${result:-0}"
}

resolve_base() {
  case "$1" in
    GRADLE_USER_HOME) printf '%s' "${GRADLE_USER_HOME:-$HOME/.gradle}" ;;
    PROJECT) printf '%s' "$project_dir" ;;
    *) die "unsupported base '$1' in $properties_file" ;;
  esac
}

download() {
  if command -v curl > /dev/null; then
    curl --fail --location --silent --show-error --connect-timeout 15 --retry 2 --output "$2" "$1"
  elif command -v wget > /dev/null; then
    wget --quiet --timeout=15 --tries=3 --output-document="$2" "$1"
  else
    die "curl or wget is required"
  fi
}

[ -f "$properties_file" ] || die "$properties_file not found"

distribution_url="$(read_property distributionUrl)"
expected_sha256="$(read_property distributionSha256Sum)"
zip_store_base="$(read_property zipStoreBase)"
zip_store_path="$(read_property zipStorePath)"

[ -n "$distribution_url" ] || die "distributionUrl is not set in $properties_file"

zip_name="${distribution_url##*/}"
dist_name="${zip_name%.zip}"
url_hash="$(wrapper_url_hash "$distribution_url")"
target_dir="$(resolve_base "${zip_store_base:-GRADLE_USER_HOME}")/${zip_store_path:-wrapper/dists}/$dist_name/$url_hash"
target_zip="$target_dir/$zip_name"

if [ -f "$target_zip.ok" ] || [ -f "$target_zip" ]; then
  echo "$dist_name is already in the wrapper cache: $target_dir"
  exit 0
fi

mkdir -p "$target_dir"
part_file="$target_zip.mirror.part"
trap 'rm -f "$part_file"' EXIT

downloaded=false
for mirror in "${MIRRORS[@]}"; do
  echo "Downloading $mirror$zip_name"
  if download "$mirror$zip_name" "$part_file"; then
    downloaded=true
    break
  fi
  echo "Download from $mirror failed, trying next mirror" >&2
  rm -f "$part_file"
done

[ "$downloaded" = true ] || die "could not download $zip_name from any mirror"

if [ -n "$expected_sha256" ]; then
  actual_sha256="$(sha256_file "$part_file")"
  if [ "$actual_sha256" != "$expected_sha256" ]; then
    die "SHA-256 mismatch for $zip_name: expected $expected_sha256, got $actual_sha256"
  fi
  echo "SHA-256 verified"
else
  echo "warning: distributionSha256Sum is not set, so the download could not be verified" >&2
fi

mv "$part_file" "$target_zip"
echo "Placed $zip_name in $target_dir"
echo "The next ./gradlew run will unpack it without downloading from services.gradle.org."
