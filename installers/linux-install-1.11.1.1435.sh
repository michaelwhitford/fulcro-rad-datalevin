#!/usr/bin/env bash

set -euo pipefail

# Clojure CLI Tools installer for Linux
# Version: 1.11.1.1435

usage() {
  cat <<EOF
Installs the Clojure command line tools.

Usage:
  $(basename "$0") [PREFIX]

Options:
  PREFIX  Installation directory prefix (default: /usr/local)
EOF
}

# Default installation prefix
prefix="${1:-/usr/local}"
bindir="$prefix/bin"
libdir="$prefix/lib/clojure"
libexecdir="$libdir/libexec"
mandir="$prefix/share/man/man1"

echo "Installing Clojure CLI tools to $prefix"

# Create directories
mkdir -p "$bindir" "$libdir" "$libexecdir" "$mandir"

# Download and extract Clojure
version="1.11.1.1435"
download_url="https://download.clojure.org/install/clojure-tools-${version}.tar.gz"
tmpdir=$(mktemp -d)
archive="$tmpdir/clojure-tools.tar.gz"

echo "Downloading Clojure tools version $version..."
if command -v wget > /dev/null 2>&1; then
    wget -q -O "$archive" "$download_url"
elif command -v curl > /dev/null 2>&1; then
    curl -sL -o "$archive" "$download_url"
else
    echo "Error: wget or curl required for download"
    exit 1
fi

echo "Extracting archive..."
tar -xzf "$archive" -C "$tmpdir"

# Find extracted directory
extracted_dir="$tmpdir/clojure-tools"

# Install configuration files (all go in libdir like official installer)
echo "Installing configuration files..."
if [ -f "$extracted_dir/deps.edn" ]; then
    cp "$extracted_dir/deps.edn" "$libdir/"
fi
if [ -f "$extracted_dir/example-deps.edn" ]; then
    cp "$extracted_dir/example-deps.edn" "$libdir/"
fi
if [ -f "$extracted_dir/tools.edn" ]; then
    cp "$extracted_dir/tools.edn" "$libdir/"
fi

# Install JAR files
echo "Installing JAR files..."
cp "$extracted_dir/exec.jar" "$libexecdir/"
cp "$extracted_dir/clojure-tools-${version}.jar" "$libexecdir/"

# Install executables with path substitution
echo "Installing executables..."
# PREFIX should point to libdir (where deps.edn and JARs are), not prefix root
sed "s|PREFIX|$libdir|g" "$extracted_dir/clojure" > "$bindir/clojure"
chmod +x "$bindir/clojure"

sed "s|BINDIR|$bindir|g" "$extracted_dir/clj" > "$bindir/clj"
chmod +x "$bindir/clj"

# Install man pages
echo "Installing man pages..."
if [ -f "$extracted_dir/clojure.1" ]; then
    cp "$extracted_dir/clojure.1" "$mandir/"
fi
if [ -f "$extracted_dir/clj.1" ]; then
    cp "$extracted_dir/clj.1" "$mandir/"
fi

# Cleanup
echo "Cleaning up..."
rm -rf "$tmpdir"

echo "Installation complete!"
echo "Clojure CLI tools installed to $prefix"
echo "Executables: $bindir/clj and $bindir/clojure"
