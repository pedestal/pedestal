#!/bin/bash
set -euo pipefail

clojure -T:build war

docker build . -t war-demo

docker run -p 8080:8080 war-demo
