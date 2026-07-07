#!/bin/bash
GRADLE_HOME=""
for ver in /opt/gradle-8.10.2 /opt/gradle-8.14.5 /opt/gradle-8.7 /opt/gradle-8.5; do
    if [ -d "$ver" ]; then GRADLE_HOME="$ver"; break; fi
done
if [ -z "$GRADLE_HOME" ]; then echo "ERROR: No local Gradle"; exit 1; fi
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
exec "$GRADLE_HOME/bin/gradle" --no-daemon "$@"
