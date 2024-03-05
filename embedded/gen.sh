#!/bin/bash
set -euo pipefail
IFS=$'\n\t'

# Script to generate an example, used when debugging the
# template itself.

if [ -d target/out ]; then
  rm -rf target/out/*
fi

clj -Sdeps '{:deps {io.pedestal/pedestal.embedded {:local/root "."}}}' \
  -Tnew \
  create :template io.pedestal/embedded \
  :name com.blueant/peripheral \
  :target-dir target/out \
  :overwrite true

tree target/out
