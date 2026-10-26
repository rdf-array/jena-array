#!/bin/sh
# rdf-array: refresh the copy of shexTest-contrib, as ../shexTest holds a copy of shexTest.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git clone --quiet --depth 1 https://github.com/rdf-array/shexTest-contrib.git "$tmp/shexTest-contrib"
git -C "$tmp/shexTest-contrib" rev-parse HEAD > "$here/shexTest-contrib.commit"
for d in schemas-contrib validation-contrib negativeSyntax-contrib; do
  rm -rf "$here/$d"
  cp -R "$tmp/shexTest-contrib/$d" "$here/"
done
# As in ../shexTest: the manifests' @base names shexTest's GitHub location, and the runner reads
# the files beside the manifest only when the entries resolve against the copy.
for m in "$here"/*/manifest.ttl; do
  sed 's/^@base /## @base /' "$m" > "$m.tmp" && mv "$m.tmp" "$m"
done
