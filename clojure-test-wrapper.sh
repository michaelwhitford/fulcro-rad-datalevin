#!/bin/bash
# Clojure Test Wrapper Script
# This script configures the environment and runs Clojure tests

# Add Clojure CLI to PATH
export PATH=$HOME/.local/bin:$PATH

# Configure DNS
if [ ! -s /etc/resolv.conf ]; then
    echo "nameserver 8.8.8.8" > /etc/resolv.conf 2>/dev/null || echo "Warning: Could not configure DNS"
fi

# Note: This environment uses an egress proxy for internet access
# curl works automatically via HTTP_PROXY environment variables
# Java requires additional configuration which has proven challenging

# If offline mode is needed:
# clojure -Sforce -Spath  # This forces classpath resolution

# Run tests with basic configuration
echo "Running tests..."
clojure -M:run-tests "$@"
