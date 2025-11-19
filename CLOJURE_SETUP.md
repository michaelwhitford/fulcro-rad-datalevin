# Clojure Test Setup

This document describes the Clojure runtime configuration for running tests in this repository.

## Installation

Clojure CLI tools have been installed to `$HOME/.local/bin/`.

```bash
# Version installed
clojure --version
# Clojure CLI version 1.12.3.1577
```

## Configuration

### DNS Configuration
DNS servers have been configured in `/etc/resolv.conf`:
```
nameserver 8.8.8.8
nameserver 8.8.4.4
```

### Maven Configuration
Maven settings are located at `$HOME/.m2/settings.xml` with proxy configuration.

### Running Tests

```bash
# Add Clojure to PATH
export PATH=$HOME/.local/bin:$PATH

# Run tests
clojure -M:run-tests
```

Or use the wrapper script:
```bash
./clojure-test-wrapper.sh
```

## Known Issues

### Proxy Authentication
The runtime environment uses an egress proxy for internet access. While `curl` works seamlessly with the proxy via `HTTP_PROXY` and `HTTPS_PROXY` environment variables, Java's HTTP client has difficulty with the proxy's authentication mechanism, resulting in 401 Unauthorized errors when downloading Maven dependencies.

**Workaround**: Dependencies need to be pre-downloaded or the Maven local repository needs to be populated beforehand.

### Dependency Download Script
A dependency download script (`download-deps.sh`) is available that uses `curl` to download dependencies directly. However, some dependencies may require Clojars repository instead of Maven Central.

## Test Configuration

The test configuration is defined in `deps.edn`:
- Test runner: Kaocha (lambdaisland/kaocha)
- Test path: `src/test`
- JVM opts: `--enable-native-access=ALL-UNNAMED` (required for native Datalevin access)

## Repository Configuration

To add Clojars repository support, you can modify `deps.edn` to include:
```clojure
:mvn/repos {
  "central" {:url "https://repo1.maven.org/maven2/"}
  "clojars" {:url "https://repo.clojars.org/"}
}
```

## Files Created

- `clojure-test-wrapper.sh` - Wrapper script for running tests
- `download-deps.sh` - Script to manually download dependencies
- `run-clojure-tests.sh` - Alternative test runner with proxy configuration
- `$HOME/.m2/settings.xml` - Maven proxy configuration
