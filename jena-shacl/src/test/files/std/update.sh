#!/bin/sh
# rdf-array: refresh this copy of the W3C SHACL test suite from data-shapes-array (the rdf-array fork of
# w3c/data-shapes, branch rdf-sequences): data-shapes-test-suite/tests here, and SHACL 1.2's list-constraint
# tests in ../std12-lists. Only files whose content differs (line endings aside) are copied, and
# sparql/pre-binding/manifest.ttl keeps jena's local exclusions (see README).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git clone --quiet --depth 1 --branch rdf-sequences https://github.com/rdf-array/data-shapes-array.git "$tmp/fork"
git -C "$tmp/fork" rev-parse HEAD > "$here/data-shapes-array.commit"
copy() {  # copy SRC-DIR DST-DIR
  (cd "$1" && find . -type f) | while read -r f; do
    case "$f" in ./sparql/pre-binding/manifest.ttl) continue ;; esac
    mkdir -p "$2/$(dirname "$f")"
    if ! [ -f "$2/$f" ] || ! diff -q --strip-trailing-cr "$1/$f" "$2/$f" >/dev/null; then
      cp "$1/$f" "$2/$f"
    fi
  done
}
copy "$tmp/fork/data-shapes-test-suite/tests" "$here"
lists="$here/../std12-lists"
for d in node property; do
  for t in memberShape-001 minListLength-001 maxListLength-001 uniqueMembers-001; do
    mkdir -p "$lists/core/$d"
    cp "$tmp/fork/shacl12-test-suite/tests/core/$d/$t.ttl" "$lists/core/$d/$t.ttl"
  done
done
