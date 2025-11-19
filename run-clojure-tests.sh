#!/bin/bash

# Extract proxy components from HTTPS_PROXY
# Format: http://user:pass@host:port
if [ -n "$HTTPS_PROXY" ]; then
    # Parse the proxy URL
    PROXY_PROTO=$(echo "$HTTPS_PROXY" | sed -E 's|^([^:]+)://.*|\1|')
    PROXY_HOST=$(echo "$HTTPS_PROXY" | sed -E 's|^[^:]+://[^@]+@([^:]+):.*|\1|')
    PROXY_PORT=$(echo "$HTTPS_PROXY" | sed -E 's|^[^:]+://[^@]+@[^:]+:([0-9]+)$|\1|')

    echo "Using proxy: $PROXY_HOST:$PROXY_PORT"

    # Set Java proxy system properties
    export PATH=$HOME/.local/bin:$PATH
    clojure \
        -J-Dhttps.proxyHost="$PROXY_HOST" \
        -J-Dhttps.proxyPort="$PROXY_PORT" \
        -J-Dhttp.proxyHost="$PROXY_HOST" \
        -J-Dhttp.proxyPort="$PROXY_PORT" \
        -J-Djava.net.useSystemProxies=true \
        -M:run-tests
else
    echo "No proxy configured"
    export PATH=$HOME/.local/bin:$PATH
    clojure -M:run-tests
fi
