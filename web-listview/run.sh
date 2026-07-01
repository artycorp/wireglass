#!/usr/bin/env bash
# Run jmeter-web-listview with a flat (real-file) classpath.
#
# Why not `java -jar`? Spring Boot nests dependencies inside the executable jar
# under BOOT-INF/lib. JMeter's embedded engine (jmeter-java-dsl) resolves the jar
# path of each test-element class via new File(codeSource.toURI()), which fails on
# nested-jar URIs ("URI is not hierarchical"). A flat classpath keeps every
# dependency as a real file and avoids this entirely.
#
# You can also run via:  mvn -pl web-listview -am spring-boot:run
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR/web-listview"

echo "[1/3] Compiling & packaging (skip tests)..."
mvn -q -pl web-listview -am -DskipTests install

echo "[2/3] Resolving dependency classpath..."
CP_FILE="$(mktemp)"
mvn -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
CLASSPATH="$(cat "$CP_FILE")"
rm -f "$CP_FILE"

echo "[3/3] Starting application on http://localhost:8080 ..."
exec java -cp "target/classes:$CLASSPATH" com.artembelikov.listview.ListViewApplication
