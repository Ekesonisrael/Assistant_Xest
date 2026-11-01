#!/usr/bin/env sh
# Lightweight Gradle launcher delegating to local gradle or wrapper jar
APP_HOME=$( cd "${0%/*}" && pwd -P ) || exit
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ -f "$WRAPPER_JAR" ]; then
    exec java -Xmx64m -Xms64m -jar "$WRAPPER_JAR" "$@"
elif command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    echo "Error: gradle-wrapper.jar not found and 'gradle' is not installed on PATH." >&2
    exit 1
fi
