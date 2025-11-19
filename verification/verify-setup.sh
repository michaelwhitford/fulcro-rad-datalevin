#!/usr/bin/env bash

# Comprehensive verification script for Clojure setup in Claude Code
# Tests all aspects of the installation

set -euo pipefail

PROXY_PORT="${PROXY_PORT:-8888}"
PASS=0
FAIL=0
WARN=0

echo "===================================="
echo "Clojure Setup Verification"
echo "===================================="
echo ""

# ============================================================================
# Test 1: Clojure CLI Installation
# ============================================================================

echo "[Test 1/7] Clojure CLI Installation"
if command -v clojure &> /dev/null; then
    VERSION=$(clojure --version 2>&1 | head -n1 || echo "unknown")
    echo "  [PASS] Clojure CLI found: $VERSION"
    ((PASS++))
else
    echo "  [FAIL] Clojure CLI not found in PATH"
    ((FAIL++))
fi
echo ""

# ============================================================================
# Test 2: Proxy Wrapper Process
# ============================================================================

echo "[Test 2/7] Proxy Wrapper Process"
PROXY_RUNNING=false

# Check if process is running
if [ -f ~/.clojure-setup/proxy-wrapper.pid ]; then
    PROXY_PID=$(cat ~/.clojure-setup/proxy-wrapper.pid)
    if kill -0 $PROXY_PID 2>/dev/null; then
        echo "  [PASS] Proxy wrapper running (PID: $PROXY_PID)"
        PROXY_RUNNING=true
        ((PASS++))
    else
        echo "  [FAIL] Proxy wrapper PID file exists but process not running"
        ((FAIL++))
    fi
else
    echo "  [WARN] No proxy wrapper PID file found"
    ((WARN++))
fi

# Check if port is listening
PORT_LISTENING=false
if command -v netstat &> /dev/null; then
    if netstat -tuln 2>/dev/null | grep -q ":$PROXY_PORT "; then
        PORT_LISTENING=true
    fi
elif command -v ss &> /dev/null; then
    if ss -tuln 2>/dev/null | grep -q ":$PROXY_PORT "; then
        PORT_LISTENING=true
    fi
fi

if [ "$PORT_LISTENING" = true ]; then
    echo "  [PASS] Port $PROXY_PORT is listening"
    ((PASS++))
else
    echo "  [FAIL] Port $PROXY_PORT is not listening"
    ((FAIL++))
fi

# Check log file
if [ -f ~/.clojure-setup/logs/proxy-wrapper.log ]; then
    LOG_SIZE=$(wc -l < ~/.clojure-setup/logs/proxy-wrapper.log)
    echo "  [PASS] Proxy log file exists ($LOG_SIZE lines)"
    ((PASS++))
else
    echo "  [WARN] Proxy log file not found"
    ((WARN++))
fi
echo ""

# ============================================================================
# Test 3: Configuration Files
# ============================================================================

echo "[Test 3/7] Configuration Files"

# Maven settings
if [ -f ~/.m2/settings.xml ]; then
    if grep -q "127.0.0.1" ~/.m2/settings.xml && grep -q "$PROXY_PORT" ~/.m2/settings.xml; then
        echo "  [PASS] Maven settings.xml configured correctly"
        ((PASS++))
    else
        echo "  [FAIL] Maven settings.xml exists but not configured correctly"
        ((FAIL++))
    fi
else
    echo "  [FAIL] Maven settings.xml not found"
    ((FAIL++))
fi

# Gradle properties
if [ -f ~/.gradle/gradle.properties ]; then
    if grep -q "127.0.0.1" ~/.gradle/gradle.properties && grep -q "$PROXY_PORT" ~/.gradle/gradle.properties; then
        echo "  [PASS] Gradle properties configured correctly"
        ((PASS++))
    else
        echo "  [FAIL] Gradle properties exists but not configured correctly"
        ((FAIL++))
    fi
else
    echo "  [FAIL] Gradle properties not found"
    ((FAIL++))
fi
echo ""

# ============================================================================
# Test 4: Environment Variables
# ============================================================================

echo "[Test 4/7] Environment Variables"

if [ -n "${http_proxy:-}" ]; then
    echo "  [PASS] http_proxy is set: $http_proxy"
    ((PASS++))
else
    echo "  [FAIL] http_proxy not set"
    ((FAIL++))
fi

if [ -n "${JAVA_TOOL_OPTIONS:-}" ]; then
    if echo "$JAVA_TOOL_OPTIONS" | grep -q "proxyHost" && echo "$JAVA_TOOL_OPTIONS" | grep -q "$PROXY_PORT"; then
        echo "  [PASS] JAVA_TOOL_OPTIONS configured correctly"
        ((PASS++))
    else
        echo "  [WARN] JAVA_TOOL_OPTIONS set but may not be configured correctly"
        ((WARN++))
    fi
else
    echo "  [WARN] JAVA_TOOL_OPTIONS not set (run: source setup/setup-clojure.sh)"
    ((WARN++))
fi
echo ""

# ============================================================================
# Test 5: Dependency Resolution
# ============================================================================

echo "[Test 5/7] Dependency Resolution"

# Test Maven Central
echo "  Testing Maven Central..."
if timeout 30 clojure -Sdeps '{:deps {org.clojure/tools.logging {:mvn/version "1.2.4"}}}' -M -e '(println "Maven Central: OK")' &> /dev/null; then
    echo "  [PASS] Can resolve dependencies from Maven Central"
    ((PASS++))
else
    echo "  [FAIL] Cannot resolve dependencies from Maven Central"
    ((FAIL++))
fi

# Test Clojars
echo "  Testing Clojars..."
if timeout 30 clojure -Sdeps '{:deps {com.fulcrologic/fulcro {:mvn/version "3.7.3"}}}' -M -e '(println "Clojars: OK")' &> /dev/null; then
    echo "  [PASS] Can resolve dependencies from Clojars"
    ((PASS++))
else
    echo "  [FAIL] Cannot resolve dependencies from Clojars"
    ((FAIL++))
fi
echo ""

# ============================================================================
# Test 6: Code Execution
# ============================================================================

echo "[Test 6/7] Code Execution"

RESULT=$(clojure -M -e '(+ 1 2 3)' 2>&1 || echo "ERROR")
if [ "$RESULT" = "6" ]; then
    echo "  [PASS] Can execute Clojure code"
    ((PASS++))
else
    echo "  [FAIL] Cannot execute Clojure code (got: $RESULT)"
    ((FAIL++))
fi
echo ""

# ============================================================================
# Test 7: Project Test
# ============================================================================

echo "[Test 7/7] Project Test"

# Try to run tests in current project if they exist
if [ -f "deps.edn" ]; then
    echo "  Checking project deps.edn..."
    if clojure -Spath &> /dev/null; then
        echo "  [PASS] Can build classpath for current project"
        ((PASS++))
    else
        echo "  [WARN] Cannot build classpath (may need dependencies)"
        ((WARN++))
    fi
else
    echo "  [WARN] No deps.edn in current directory"
    ((WARN++))
fi
echo ""

# ============================================================================
# Summary
# ============================================================================

echo "===================================="
echo "Verification Summary"
echo "===================================="
echo "Passed: $PASS"
echo "Failed: $FAIL"
echo "Warnings: $WARN"
echo ""

if [ $FAIL -eq 0 ]; then
    echo "Result: ALL TESTS PASSED ✓"
    echo ""
    echo "Your Clojure environment is fully functional!"
    exit 0
else
    echo "Result: SOME TESTS FAILED ✗"
    echo ""
    echo "Please run: source setup/setup-clojure.sh"
    echo "Then run this verification script again."
    exit 1
fi
