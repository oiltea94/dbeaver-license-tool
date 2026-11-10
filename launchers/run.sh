#!/bin/sh
# JAVA_CMD is an executable path, not a command with extra arguments.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
JAVA_EXE=${JAVA_CMD:-}
try_java() {
    if [ -z "$JAVA_EXE" ] && [ -x "$1" ]; then JAVA_EXE=$1; fi
}
if [ -z "$JAVA_EXE" ]; then
    if [ -n "${JAVA_HOME:-}" ]; then try_java "$JAVA_HOME/bin/java"; fi
    if [ -n "${DBEAVER_HOME:-}" ]; then
        try_java "$DBEAVER_HOME/jre/bin/java"
        try_java "$DBEAVER_HOME/jre/Contents/Home/bin/java"
        try_java "$DBEAVER_HOME/Contents/Eclipse/jre/Contents/Home/bin/java"
        try_java "$DBEAVER_HOME/Contents/Eclipse/jre/bin/java"
        try_java "$DBEAVER_HOME/Contents/Home/bin/java"
    fi
    try_java "$SCRIPT_DIR/../jre/bin/java"
    try_java "$SCRIPT_DIR/../jre/Contents/Home/bin/java"
    try_java "$SCRIPT_DIR/jre/bin/java"
    try_java "$SCRIPT_DIR/jre/Contents/Home/bin/java"
    if [ -z "$JAVA_EXE" ]; then JAVA_EXE=$(command -v java 2>/dev/null) || :; fi
fi
if [ -z "$JAVA_EXE" ]; then
    printf '%s\n' 'Java 21+ required. Set JAVA_HOME, DBEAVER_HOME, or JAVA_CMD to an installed Java executable.' >&2
    exit 1
fi
JAVA_VERSION=$("$JAVA_EXE" --version 2>&1) || {
    printf '%s\n' "Cannot run Java: $JAVA_EXE" "$JAVA_VERSION" >&2
    exit 1
}
JAVA_MAJOR=$(printf '%s\n' "$JAVA_VERSION" | sed -n '1s/^[^ ]* \([0-9][0-9]*\).*/\1/p')
case "$JAVA_MAJOR" in
    ''|*[!0-9]*) printf '%s\n' "Cannot determine Java version: $JAVA_VERSION" >&2; exit 1 ;;
esac
if [ "$JAVA_MAJOR" -lt 21 ]; then
    printf '%s\n' "Java 21+ required; detected Java $JAVA_MAJOR at $JAVA_EXE" >&2
    exit 1
fi
if [ ! -f "$SCRIPT_DIR/dbeaver-license-tool.jar" ]; then
    printf '%s\n' "Missing $SCRIPT_DIR/dbeaver-license-tool.jar. Extract all package files together." >&2
    exit 1
fi
exec "$JAVA_EXE" -jar "$SCRIPT_DIR/dbeaver-license-tool.jar" "$@"
