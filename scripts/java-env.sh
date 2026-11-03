#!/usr/bin/env bash
# Sourced by launchers after loading .env. All paths are relative to the repo root.
if [[ -x .tools/jdk/bin/java ]]; then
  export JAVA_HOME="$PWD/.tools/jdk"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  WORKHUB_JAVA="$JAVA_HOME/bin/java"
else
  WORKHUB_JAVA="$(command -v java || true)"
fi
if [[ ! -x "$WORKHUB_JAVA" ]]; then
  echo 'Java 17 required: set JAVA_HOME, install Java on PATH, or run python3 scripts/bootstrap-jdk.py (Linux x86_64).' >&2
  exit 1
fi
