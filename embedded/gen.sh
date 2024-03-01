#!/bin/bash
set -euo pipefail
IFS=$'\n\t'

# Temporary script to generate an example, used when debugging the
# template itself.

rm -rf target/out

clj -Sdeps '{:deps {io.pedestal/pedestal.embedded {:local/root "."}}}' \
  -Tnew \
  create :template io.pedestal/embedded \
  :name com.blueant/peripheral \
  :target-dir target/out

  tree target/out
