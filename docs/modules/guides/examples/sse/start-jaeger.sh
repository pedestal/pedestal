#!/usr/bin/env bash
set -euo pipefail

dir=target
agent_jar=$dir/opentelemetry-javaagent.jar

if  [ ! -f $agent_jar ]; then
  echo "Downloading Open Telemetry Java Agent to target directory ..."
  if  [ ! -d $dir ]; then
    mkdir $dir
  fi
  wget --quiet \
     https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
    --output-document $agent_jar
fi

docker run -d --rm                  \
           --name jaeger            \
           -p 16686:16686           \
           -p 4318:4318             \
           jaegertracing/all-in-one \
           --collector.otlp.enabled=true

echo "Jaeger is running, execute \`docker stop jaeger\` to stop it."

# Regrettably Mac specific

open http://localhost:16686/search

