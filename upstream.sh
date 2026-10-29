#!/usr/bin/env bash
# upstream.sh - see ONLY what the developers of Lune and decent-player changed after a recorded starting point.
#
# Transient is a copy of those two projects (it shares no git history with them), so a normal "git merge" cannot tell
# your changes from theirs. This script instead remembers, for each project, the last upstream commit you have dealt
# with (the "baseline") and asks git for everything after it.
#
#   bash upstream.sh setup            first time only: add the two remotes and record today as the baseline
#   bash upstream.sh check            what is new upstream since your baseline (nothing is changed in your project)
#   bash upstream.sh diff  lune       save the changes as a .diff file (to send to Claude, or to read)
#   bash upstream.sh apply lune       merge the changes into your working tree (conflicts are marked in the files)
#   bash upstream.sh apply lune 8bac546   merge just one upstream commit
#   bash upstream.sh mark  lune       you are done with everything up to now: move the baseline forward
#
# Names: lune (MrDemonc/Lune, folder app/)   decent (Ma145/decent-player, folder libs/ -> your decent/)
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "ERROR: run this inside your Transient clone" >&2; exit 1; }
cd "$ROOT"
CONF=.upstream/baselines.env

declare -A URL=( [lune]="https://github.com/MrDemonc/Lune.git" [decent]="https://github.com/Ma145/decent-player.git" )
declare -A BRANCH=( [lune]="main" [decent]="main" )
declare -A SRC=( [lune]="app" [decent]="libs" )          # folder inside the upstream repo that Transient uses
declare -A DST=( [lune]="" [decent]="decent" )           # where it lives in Transient ("" = same place)
declare -A SLUG=( [lune]="MrDemonc/Lune" [decent]="Ma145/decent-player" )
NAMES=(lune decent)

die() { echo "ERROR: $*" >&2; exit 1; }
valid() { [[ -n "${URL[$1]:-}" ]] || die "unknown name '$1' (use: lune or decent)"; }
head_of() { git rev-parse "$1/${BRANCH[$1]}"; }
key() { echo "$(echo "$1" | tr a-z A-Z)_BASE"; }
base_of() { [[ -f $CONF ]] || die "no baseline yet. Run:  bash upstream.sh setup"; local v; v=$(grep "^$(key "$1")=" "$CONF" | cut -d= -f2 || true); [[ -n $v ]] || die "no baseline for $1 in $CONF"; echo "$v"; }
set_base() {
  mkdir -p .upstream; touch "$CONF"
  grep -v "^$(key "$1")=" "$CONF" > "$CONF.tmp" || true
  echo "$(key "$1")=$2" >> "$CONF.tmp"; sort "$CONF.tmp" > "$CONF"; rm -f "$CONF.tmp"
}
ensure_remote() {
  local n=$1 cur
  if cur=$(git config --get "remote.$n.url" 2>/dev/null); then
    [[ "$cur" == "${URL[$n]}" || "$cur" == "${URL[$n]%.git}" ]] || die "remote '$n' points to $cur, expected ${URL[$n]}. Fix with: git remote set-url $n ${URL[$n]}"
  else
    git remote add "$n" "${URL[$n]}"
  fi
}
fetch_all() { for n in "$@"; do ensure_remote "$n"; echo "fetching $n ..."; git fetch --no-tags --quiet "$n" "${BRANCH[$n]}"; done; }
patch_for() {   # patch_for name [commit]  -> writes the patch to stdout
  local n=$1 c=${2:-} s=${SRC[$1]}
  if [[ -n $c ]]; then git diff --binary "$c^" "$c" -- "$s"; else git diff --binary "$(base_of "$n")" "$(head_of "$n")" -- "$s"; fi
}
map_path() { local n=$1 p=$2; if [[ -n ${DST[$n]} ]]; then echo "${DST[$n]}/${p#"${SRC[$n]}"/}"; else echo "$p"; fi; }
is_binary() { git diff --numstat "$1" "$2" -- "$3" | grep -q '^-	-	'; }
merge_in() {   # merge_in name base head : three-way merge of every upstream file change into the working tree
  local n=$1 b=$2 h=$3 s=${SRC[$1]} w; w=$(mktemp -d)
  local -a ok=() conflict=() added=() removed=() skipped=() kept=()
  : > "$w/empty"
  while IFS=$'\t' read -r st path; do
    [[ -n $path ]] || continue
    local t; t=$(map_path "$n" "$path")
    case $st in
      A)
        git show "$h:$path" > "$w/theirs"
        if [[ ! -e $t ]]; then mkdir -p "$(dirname "$t")"; cp "$w/theirs" "$t"; added+=("$t")
        elif cmp -s "$w/theirs" "$t"; then :
        else cp "$w/theirs" "$w/x"; git merge-file -L Transient -L nothing -L upstream "$t" "$w/empty" "$w/x" || true; conflict+=("$t (exists in both)"); fi ;;
      D)
        if [[ ! -e $t ]]; then :
        else git show "$b:$path" > "$w/base"
          if cmp -s "$w/base" "$t"; then rm -f "$t"; removed+=("$t"); else kept+=("$t (upstream deleted it, but you changed it)"); fi; fi ;;
      M)
        if [[ ! -e $t ]]; then skipped+=("$t (not in Transient)"); continue; fi
        git show "$b:$path" > "$w/base"; git show "$h:$path" > "$w/theirs"
        if cmp -s "$w/base" "$t"; then cp "$w/theirs" "$t"; ok+=("$t")
        elif is_binary "$b" "$h" "$path"; then kept+=("$t (binary; upstream changed it, you changed it too)")
        else
          if git merge-file -L Transient -L upstream-old -L upstream-new "$t" "$w/base" "$w/theirs"; then ok+=("$t"); else conflict+=("$t"); fi
        fi ;;
    esac
  done < <(git diff --name-status --no-renames "$b" "$h" -- "$s")
  rm -rf "$w"
  echo; echo "Result of merging $n:"
  echo "  merged cleanly : ${#ok[@]}"; for x in "${ok[@]}"; do echo "     $x"; done
  echo "  new files      : ${#added[@]}"; for x in "${added[@]}"; do echo "     $x"; done
  echo "  removed        : ${#removed[@]}"; for x in "${removed[@]}"; do echo "     $x"; done
  if (( ${#skipped[@]} )); then echo "  skipped (file does not exist in Transient):"; for x in "${skipped[@]}"; do echo "     $x"; done; fi
  if (( ${#kept[@]} )); then echo "  left alone, decide by hand:"; for x in "${kept[@]}"; do echo "     $x"; done; fi
  if (( ${#conflict[@]} )); then echo "  CONFLICTS - open these and look for <<<<<<<  =======  >>>>>>> :"; for x in "${conflict[@]}"; do echo "     $x"; done; fi
}

cmd=${1:-help}; shift || true
case $cmd in
setup)
  fetch_all "${NAMES[@]}"
  if [[ -f $CONF && ${1:-} != "--force" ]]; then echo "Baselines already recorded (use --force to overwrite):"; cat "$CONF"; exit 0; fi
  for n in "${NAMES[@]}"; do set_base "$n" "$(head_of "$n")"; done
  echo "Baseline recorded - from now on 'check' shows only what is newer than this:"; cat "$CONF"
  echo; echo "Commit it so it is kept:  git add .upstream && git commit -m 'Record upstream baselines' && git push"
  ;;
check)
  want=("${@:-${NAMES[@]}}"); fetch_all "${want[@]}"
  for n in "${want[@]}"; do
    valid "$n"; b=$(base_of "$n"); h=$(head_of "$n"); s=${SRC[$n]}
    total=$(git rev-list --count "$b..$h")
    echo; echo "=== $n  (${SLUG[$n]})   baseline ${b:0:7}  ->  latest ${h:0:7}"
    if [[ $total == 0 ]]; then echo "  nothing new."; continue; fi
    mine=$(git rev-list --count "$b..$h" -- "$s")
    echo "  $total new commit(s); $mine touch $s/ (the part Transient uses)."
    git log --reverse --date=short --pretty='  %ad  %h  %s  (%an)' "$b..$h" -- "$s" | head -60
    echo "  files changed in $s/:"; git diff --stat "$b" "$h" -- "$s" | tail -n 25 | sed 's/^/   /'
    other=$(git diff --name-only "$b" "$h" | grep -v "^$s/" | grep -Ev '^(fastlane|docs|\.github|README|LICENSE|NOTICE|CONTRIBUTING)' || true)
    if [[ -n $other ]]; then echo "  other files changed (build files and so on - look at these by hand):"; echo "$other" | head -15 | sed 's/^/   /'; fi
    echo "  see it on GitHub:  https://github.com/${SLUG[$n]}/compare/${b}...${h}"
  done
  echo; echo "Next: 'diff <name>' to save it, or 'apply <name>' to merge it. Then 'mark <name>'."
  ;;
diff)
  n=${1:-}; valid "$n"; fetch_all "$n"; c=${2:-}
  out_dir="$HOME/upstream-patches"; [[ -d "$HOME/storage/downloads" ]] && out_dir="$HOME/storage/downloads/upstream-patches"
  mkdir -p "$out_dir"; f="$out_dir/$n-$(date +%F)${c:+-${c:0:7}}.diff"
  patch_for "$n" "$c" > "$f"
  git log --date=short --pretty='%ad %h %s (%an)' "$(base_of "$n")..$(head_of "$n")" -- "${SRC[$n]}" > "${f%.diff}-commits.txt" || true
  echo "Saved: $f  ($(wc -l < "$f") lines)"; echo "Saved: ${f%.diff}-commits.txt"
  ;;
apply)
  n=${1:-}; valid "$n"; fetch_all "$n"; c=${2:-}
  [[ -z $(git status --porcelain --untracked-files=no) ]] || die "you have uncommitted changes. Commit or stash them first, so this merge is easy to undo."
  if [[ -n $c ]]; then b=$(git rev-parse "$c^"); h=$(git rev-parse "$c"); else b=$(base_of "$n"); h=$(head_of "$n"); fi
  [[ $b != "$h" ]] || { echo "Nothing to apply."; exit 0; }
  echo "Merging upstream changes ${b:0:7}..${h:0:7} into your files (your own changes are kept) ..."
  merge_in "$n" "$b" "$h"
  dst=${DST[$n]:-${SRC[$n]}}
  echo; echo "Next: build and test, then commit. To undo all of this:  git reset --hard HEAD && git clean -fd -- $dst"
  echo "When you are done with everything up to now:  bash upstream.sh mark $n${c:+   (only if you applied everything, not just one commit)}"
  ;;
mark)
  n=${1:-}; valid "$n"; fetch_all "$n"; h=${2:-$(head_of "$n")}; h=$(git rev-parse "$h")
  set_base "$n" "$h"; echo "Baseline for $n is now ${h:0:7}."; echo "Commit it:  git add .upstream && git commit -m 'Upstream baseline: $n' && git push"
  ;;
*) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
