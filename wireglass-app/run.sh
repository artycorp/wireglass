#!/usr/bin/env bash
# Run Wireglass with a flat (real-file) classpath.
#
# Why not `java -jar`? Spring Boot nests dependencies inside the executable jar
# under BOOT-INF/lib. JMeter's embedded engine (jmeter-java-dsl) resolves the jar
# path of each test-element class via new File(codeSource.toURI()), which fails on
# nested-jar URIs ("URI is not hierarchical"). A flat classpath keeps every
# dependency as a real file and avoids this entirely.
#
# You can also run from the repo root, in two steps:
#   mvn -pl wireglass-app -am -DskipTests install
#   mvn -pl wireglass-app org.springframework.boot:spring-boot-maven-plugin:run
# Keep them separate: a fully-qualified goal runs on every module -am pulls into
# the reactor, and wireglass-client has no main class, so combining them fails
# with "Unable to find a suitable main class".
# (the short `spring-boot:run` prefix only resolves from inside wireglass-app/)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "[1/3] Compiling & packaging (skip tests)..."
mvn -q -f "$ROOT_DIR/pom.xml" -pl wireglass-app -am -DskipTests install

cd "$ROOT_DIR/wireglass-app"

echo "[2/3] Resolving dependency classpath..."
CP_FILE="$(mktemp)"
mvn -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
CLASSPATH="$(cat "$CP_FILE")"
rm -f "$CP_FILE"

echo "[3/3] Starting application on http://localhost:8080 ..."
exec java -cp "target/classes:$CLASSPATH" com.wireglass.listview.ListViewApplication
