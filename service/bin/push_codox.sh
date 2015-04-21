#!/bin/bash
set -e

# Script to generate docs and push to github pages.
# https://github.com/weavejester/codox/wiki/Deploying-to-GitHub-Pages
cd `dirname $0`
cd ..

git fetch --tags
latestTag=$(git describe --tags `git rev-list --tags --max-count=1`)
git checkout $latestTag

lein doc
pushd doc
git checkout gh-pages # To be sure you're on the right branch
git add .
git commit -am "new documentation push."
git push -u origin gh-pages
popd
git checkout -
