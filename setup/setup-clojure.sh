#!/usr/bin/env bash

# Idempotent Clojure development environment setup for Claude Code
# This script must be sourced, not executed: source setup/setup-clojure.sh

set -euo pipefail

# ============================================================================
# Configuration
# ============================================================================

PROXY_PORT="${PROXY_PORT:-8888}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Validate proxy port
if ! [[ "$PROXY_PORT" =~ ^[0-9]+$ ]] || [ "$PROXY_PORT" -lt 1024 ] || [ "$PROXY_PORT" -gt 65535 ]; then
    echo "[ERROR] Invalid PROXY_PORT: $PROXY_PORT (must be 1024-65535)"
    return 1
fi

# Check for upstream proxy
if [ -z "${http_proxy:-}" ]; then
    echo "[ERROR] http_proxy environment variable not set"
    return 1
fi

echo "===================================="
echo "Clojure Development Setup"
echo "===================================="
echo "Proxy port: $PROXY_PORT"
echo "Upstream proxy: $http_proxy"
echo ""

# ============================================================================
# Step 1: Install Clojure CLI Tools
# ============================================================================

echo "[Step 1/5] Installing Clojure CLI..."

if command -v clojure &> /dev/null; then
    CLOJURE_VERSION=$(clojure --version 2>&1 | head -n1 || echo "unknown")
    echo "[OK] Clojure already installed: $CLOJURE_VERSION"
else
    echo "Installing Clojure CLI tools..."

    # Make installer executable
    chmod +x "$PROJECT_ROOT/installers/linux-install-1.11.1.1435.sh"

    # Install to ~/.local (no sudo required)
    mkdir -p ~/.local
    "$PROJECT_ROOT/installers/linux-install-1.11.1.1435.sh" ~/.local

    # Add to PATH if not already there
    if [[ ":$PATH:" != *":$HOME/.local/bin:"* ]]; then
        export PATH="$HOME/.local/bin:$PATH"
        echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
    fi

    echo "[OK] Clojure CLI tools installed"
fi

# ============================================================================
# Step 2: Start Proxy Wrapper
# ============================================================================

echo ""
echo "[Step 2/5] Starting proxy wrapper..."

# Check if port is already in use
PORT_IN_USE=false
if command -v netstat &> /dev/null; then
    if netstat -tuln 2>/dev/null | grep -q ":$PROXY_PORT "; then
        PORT_IN_USE=true
    fi
elif command -v ss &> /dev/null; then
    if ss -tuln 2>/dev/null | grep -q ":$PROXY_PORT "; then
        PORT_IN_USE=true
    fi
fi

if [ "$PORT_IN_USE" = true ]; then
    echo "[OK] Port $PROXY_PORT already in use (proxy likely running)"
else
    echo "Starting proxy wrapper on port $PROXY_PORT..."

    # Make proxy wrapper executable
    chmod +x "$SCRIPT_DIR/proxy-wrapper.py"

    # Start proxy in background with logging
    mkdir -p ~/.clojure-setup/logs
    LOGFILE=~/.clojure-setup/logs/proxy-wrapper.log

    export PROXY_PORT
    nohup "$SCRIPT_DIR/proxy-wrapper.py" > "$LOGFILE" 2>&1 &
    PROXY_PID=$!

    echo $PROXY_PID > ~/.clojure-setup/proxy-wrapper.pid

    # Wait a moment for proxy to start
    sleep 2

    # Verify it's running
    if kill -0 $PROXY_PID 2>/dev/null; then
        echo "[OK] Proxy wrapper started (PID: $PROXY_PID)"
        echo "     Log file: $LOGFILE"
    else
        echo "[ERROR] Proxy wrapper failed to start"
        echo "     Check log: $LOGFILE"
        return 1
    fi
fi

# ============================================================================
# Step 3: Configure Maven
# ============================================================================

echo ""
echo "[Step 3/5] Configuring Maven..."

mkdir -p ~/.m2

cat > ~/.m2/settings.xml <<EOF
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              http://maven.apache.org/xsd/settings-1.0.0.xsd">
  <proxies>
    <proxy>
      <id>http-proxy</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>127.0.0.1</host>
      <port>$PROXY_PORT</port>
    </proxy>
    <proxy>
      <id>https-proxy</id>
      <active>true</active>
      <protocol>https</protocol>
      <host>127.0.0.1</host>
      <port>$PROXY_PORT</port>
    </proxy>
  </proxies>
</settings>
EOF

echo "[OK] Maven settings.xml configured"

# ============================================================================
# Step 4: Configure Java Tool Options
# ============================================================================

echo ""
echo "[Step 4/5] Configuring Java environment..."

export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=$PROXY_PORT -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=$PROXY_PORT"

# Add to bashrc if not already there
if ! grep -q "JAVA_TOOL_OPTIONS.*proxyHost" ~/.bashrc 2>/dev/null; then
    echo "export JAVA_TOOL_OPTIONS=\"-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=$PROXY_PORT -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=$PROXY_PORT\"" >> ~/.bashrc
fi

echo "[OK] JAVA_TOOL_OPTIONS configured"

# ============================================================================
# Step 5: Configure Gradle
# ============================================================================

echo ""
echo "[Step 5/5] Configuring Gradle..."

mkdir -p ~/.gradle

cat > ~/.gradle/gradle.properties <<EOF
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=$PROXY_PORT
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=$PROXY_PORT
EOF

echo "[OK] Gradle properties configured"

# ============================================================================
# Summary
# ============================================================================

echo ""
echo "===================================="
echo "Setup Complete!"
echo "===================================="
echo ""
echo "Your Clojure development environment is ready."
echo ""
echo "Quick verification:"
echo "  clojure --version"
echo "  clojure -Sdeps '{:deps {org.clojure/tools.logging {:mvn/version \"1.2.4\"}}}' -M -e '(println \"Dependencies resolved!\")'"
echo ""
echo "Proxy logs:"
echo "  tail -f ~/.clojure-setup/logs/proxy-wrapper.log"
echo ""
echo "For comprehensive verification:"
echo "  ./verification/verify-setup.sh"
echo ""
