#!/bin/bash
set -euo pipefail
IFS=$'\n\t'

# Temporary script to generate an example, used when debugging the
# template itself.

clj -Sdeps '{:deps {io.pedestal/pedestal.embedded {:local/root "."}}}' \
  -Tnew \
  create :template io.pedestal/embedded \
  :name org.example/embedded \
  :target-dir target/out \
  :overwrite :overwrite
