#!/usr/bin/env bash
set -euo pipefail
PORT="${1:-8088}"
mvn -q -DskipTests compile exec:java -Dexec.mainClass=edu.weijunyong.satedgesim.server.SatEdgeSimRestServer -Dexec.args="--port ${PORT}"
