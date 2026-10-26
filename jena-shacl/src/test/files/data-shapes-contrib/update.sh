#!/bin/sh
# rdf-array: refresh the copy of data-shapes-contrib (the SHACL-array tests no W3C suite has), as ../std
# holds a copy of data-shapes-array's SHACL suite.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git clone --quiet --depth 1 https://github.com/rdf-array/data-shapes-contrib.git "$tmp/contrib"
git -C "$tmp/contrib" rev-parse HEAD > "$here/data-shapes-contrib.commit"
rm -rf "$here/tests"
cp -R "$tmp/contrib/tests" "$here/"
