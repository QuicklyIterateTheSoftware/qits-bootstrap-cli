#!/usr/bin/env bash
# Regenerates this directory from a wrapper checkout (qits-qits) whose submodules are materialised:
#
#     src/test/resources/estate/refresh.sh <wrapper-dir>
#
# It copies exactly what CheckoutProjectSource reads, and nothing else:
#   .gitmodules                      the wrapper's own, at the top of this directory
#   <repo>/files/                    per submodule: .config/qits/release.yml and deployments.yml,
#                                    the root pom.xml and every pom its reactor reaches (modules,
#                                    transitively, never under src/main/webui), the root
#                                    package.json, its package-lock.json cut down to the entries
#                                    NpmParser reads (the direct dependencies), the Dockerfiles in
#                                    the root and in docker/, and the root .gitmodules
#   <repo>/gitlinks                  `<path> <sha>` per gitlink its HEAD records
#
# It writes only under this directory, and reads the wrapper. Needs git and jq.
set -euo pipefail

wrapper="${1:?usage: refresh.sh <wrapper-dir>}"
wrapper="$(cd "$wrapper" && pwd)"
here="$(cd "$(dirname "$0")" && pwd)"

# Everything but this script is regenerated.
find "$here" -mindepth 1 -maxdepth 1 ! -name refresh.sh -exec rm -rf {} +
cp "$wrapper/.gitmodules" "$here/.gitmodules"

is_dockerfile() {
  case "$1" in
    Dockerfile | *.Dockerfile | Dockerfile.*) return 0 ;;
    *) return 1 ;;
  esac
}

copy() { # <src-root> <dst-root> <relative path>
  mkdir -p "$(dirname "$2/$3")"
  cp "$1/$3" "$2/$3"
}

# The reactor: a pom's <module> entries, followed transitively, as ManifestReader walks them.
reactor() { # <src-root> <dst-root> <relative pom path>
  local src="$1" dst="$2" pom="$3" dir module path
  [ -f "$src/$pom" ] || return 0
  [ -f "$dst/$pom" ] && return 0
  case "$pom" in *src/main/webui*) return 0 ;; esac
  copy "$src" "$dst" "$pom"
  dir="$(dirname "$pom")"
  [ "$dir" = "." ] && dir="" || dir="$dir/"
  while IFS= read -r module; do
    module="${module#./}"
    case "$module" in
      *.xml) path="$dir$module" ;;
      *) path="$dir${module%/}/pom.xml" ;;
    esac
    reactor "$src" "$dst" "$path"
  done < <(sed -n 's#.*<module>[[:space:]]*\([^<]*[^<[:space:]]\)[[:space:]]*</module>.*#\1#p' "$src/$pom")
}

git -C "$wrapper" config -f .gitmodules --get-regexp '^submodule\..*\.path$' | while read -r _ path; do
  url="$(git -C "$wrapper" config -f .gitmodules --get "submodule.$(git -C "$wrapper" config -f .gitmodules --get-regexp '^submodule\..*\.path$' | awk -v p="$path" '$2 == p { sub(/^submodule\./, "", $1); sub(/\.path$/, "", $1); print $1 }').url")"
  repo="$(basename "${url%/}" .git)"
  src="$wrapper/$path"
  dst="$here/$repo/files"
  mkdir -p "$dst"
  if [ ! -e "$src/.git" ]; then
    echo "skipped $repo: $path holds no checkout" >&2
    continue
  fi

  for file in .config/qits/release.yml .config/qits/deployments.yml .gitmodules package.json; do
    [ -f "$src/$file" ] && copy "$src" "$dst" "$file"
  done
  reactor "$src" "$dst" pom.xml

  if [ -f "$src/package-lock.json" ]; then
    jq --slurpfile pkg "$src/package.json" '
      ([($pkg[0].dependencies // {}), ($pkg[0].devDependencies // {})] | add | keys) as $direct
      | def direct: (ltrimstr("node_modules/")) as $n | ($direct | index($n)) != null;
      {lockfileVersion}
      + (if .packages then {packages: (.packages | with_entries(select(
            .key == "" or ((.key | startswith("node_modules/")) and (.key | direct)))))} else {} end)
      + (if .dependencies then {dependencies: (.dependencies | with_entries(select(
            (.key as $k | $direct | index($k)) != null)))} else {} end)
    ' "$src/package-lock.json" > "$dst/package-lock.json"
  fi

  for dir in "" docker/; do
    [ -d "$src/$dir" ] || continue
    for file in "$src/$dir"*; do
      [ -f "$file" ] || continue
      name="$(basename "$file")"
      is_dockerfile "$name" && copy "$src" "$dst" "$dir$name"
    done
  done

  : > "$here/$repo/gitlinks"
  if [ -f "$src/.gitmodules" ]; then
    git -C "$src" config -f .gitmodules --get-regexp '^submodule\..*\.path$' | while read -r _ sub; do
      git -C "$src" ls-tree HEAD -- "$sub" | awk -v p="$sub" '$2 == "commit" { print p " " $3 }'
    done >> "$here/$repo/gitlinks"
  fi
done
