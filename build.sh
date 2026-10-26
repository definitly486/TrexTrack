#!/bin/sh
JAVA_TOOL_OPTIONS="-Dos.name=Linux" ./gradlew assembleRelease --no-daemon "$@"