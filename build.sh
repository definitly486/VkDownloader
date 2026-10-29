#!/bin/sh
set -eu
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dos.name=Linux" ./gradlew assembleDebug --no-daemon "$@"
