#!/bin/bash

set -euo pipefail

PROJECT_ROOT="/Users/espitman/Documents/Projects/Axon"
DESKTOP_DIR="$HOME/Desktop"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
JAVA_HOME="${JAVA_HOME:-/Users/espitman/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

if [ ! -x "$PROJECT_ROOT/gradlew" ]; then
  echo "❌ Error: gradlew not found at $PROJECT_ROOT/gradlew"
  exit 1
fi

echo "🚀 Building Axon release APK..."
cd "$PROJECT_ROOT"
./gradlew :app:assembleRelease

APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/release/app-release.apk"

if [ ! -f "$APK_PATH" ]; then
  echo "❌ Error: Release APK not found in $PROJECT_ROOT/app/build/outputs/apk/release"
  exit 1
fi

OUT_APK="$DESKTOP_DIR/axon-release.apk"
cp "$APK_PATH" "$OUT_APK"

echo "✅ Release APK copied to:"
echo "$OUT_APK"
