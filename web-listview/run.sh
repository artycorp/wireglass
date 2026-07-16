#!/usr/bin/env bash
# Run jmeter-web-listview with a flat (real-file) classpath.
#
# Why not `java -jar`? Spring Boot nests dependencies inside the executable jar
# under BOOT-INF/lib. JMeter's embedded engine (jmeter-java-dsl) resolves the jar
# path of each test-element class via new File(codeSource.toURI()), which fails on
# nested-jar URIs ("URI is not hierarchical"). A flat classpath keeps every
# dependency as a real file and avoids this entirely.
#
# You can also run from the repo root via:
#   mvn -pl web-listview -am org.springframework.boot:spring-boot-maven-plugin:run
# (the short `spring-boot:run` prefix only resolves from inside web-listview/)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "[1/3] Compiling & packaging (skip tests)..."
mvn -q -f "$ROOT_DIR/pom.xml" -pl web-listview -am -DskipTests install

cd "$ROOT_DIR/web-listview"

echo "[2/3] Resolving dependency classpath..."
CP_FILE="$(mktemp)"
mvn -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
CLASSPATH="$(cat "$CP_FILE")"
rm -f "$CP_FILE"

echo "[3/3] Starting application on http://localhost:8080 ..."
exec java -cp "target/classes:$CLASSPATH" com.artembelikov.listview.ListViewApplication
