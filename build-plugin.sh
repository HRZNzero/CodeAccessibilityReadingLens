#!/usr/bin/env bash
set -euo pipefail
if command -v ./gradlew >/dev/null 2>&1 && [ -x ./gradlew ]; then
  ./gradlew buildPlugin
elif command -v gradle >/dev/null 2>&1; then
  gradle buildPlugin
else
  echo "Gradle is not installed and no Gradle wrapper is present. Open this folder in IntelliJ IDEA and run the Gradle task: buildPlugin"
  exit 1
fi
