#!/bin/bash
set -e

# Function to download a Maven artifact
download_artifact() {
    local group_id=$1
    local artifact_id=$2
    local version=$3

    # Convert group ID to path
    local group_path=$(echo "$group_id" | tr '.' '/')
    local artifact_path="$HOME/.m2/repository/$group_path/$artifact_id/$version"

    # Create directory
    mkdir -p "$artifact_path"

    # Download POM
    local pom_file="$artifact_id-$version.pom"
    local pom_url="https://repo1.maven.org/maven2/$group_path/$artifact_id/$version/$pom_file"

    if [ ! -f "$artifact_path/$pom_file" ]; then
        echo "Downloading $pom_url..."
        curl -k -L -o "$artifact_path/$pom_file" "$pom_url" || echo "Failed to download POM for $group_id:$artifact_id:$version"
    fi

    # Download JAR
    local jar_file="$artifact_id-$version.jar"
    local jar_url="https://repo1.maven.org/maven2/$group_path/$artifact_id/$version/$jar_file"

    if [ ! -f "$artifact_path/$jar_file" ] || [ ! -s "$artifact_path/$jar_file" ]; then
        echo "Downloading $jar_url..."
        curl -k -L -o "$artifact_path/$jar_file" "$jar_url" || echo "Failed to download JAR for $group_id:$artifact_id:$version"
    fi
}

# Download core dependencies from deps.edn
download_artifact "org.clojure" "clojure" "1.11.1"
download_artifact "com.fulcrologic" "fulcro" "3.8.6"
download_artifact "com.fulcrologic" "fulcro-rad" "1.6.18"
download_artifact "datalevin" "datalevin" "0.9.22"
download_artifact "com.wsscode" "pathom3" "2025.01.16-alpha"
download_artifact "edn-query-language" "eql" "2025.09.27"
download_artifact "com.taoensso" "timbre" "6.8.0"
download_artifact "com.taoensso" "encore" "3.158.0"

# Download test dependencies
download_artifact "fulcrologic" "fulcro-spec" "3.1.16"
download_artifact "lambdaisland" "kaocha" "1.91.1392"

echo "Dependency download complete!"
