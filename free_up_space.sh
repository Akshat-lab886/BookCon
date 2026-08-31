#!/usr/bin/env bash
#
# free_up_space.sh — Safe, CACHE-ONLY Mac disk cleanup
#
#   WHAT IT CLEANS (user-level, app-rebuildable):
#     - ~/Library/Caches/*            (application caches)
#     - ~/Library/Developer/Xcode/DerivedData
#     - ~/Library/Developer/CoreSimulator/Caches
#     - ~/.npm/_cacache               (npm)
#     - ~/.cache                      (generic)
#     - ~/.yarn/cache                 (yarn)
#     - ~/Library/Caches/pip and pip cache purge
#     - brew cleanup (Homebrew, only if installed)
#
#   SAFETY GUARDS:
#     - Refuses to run as root / with sudo
#     - DRY-RUN by default: prints sizes, deletes NOTHING
#     - Run with --execute to actually delete
#     - Never touches /System, /Library (system), ~/Library/Logs, or home files
#
#   USAGE:
#     bash free_up_space.sh          # dry run (safe, recommended first)
#     bash free_up_space.sh --execute
#
set -uo pipefail

if [ "$(id -u)" -eq 0 ]; then
  echo "Refusing to run as root. Run it as yourself (no sudo)." >&2
  exit 1
fi

EXECUTE=0
for arg in "$@"; do
  case "$arg" in
    --execute) EXECUTE=1 ;;
    *) echo "Unknown option: $arg (use --execute to delete)" >&2 ;;
  esac
done

echo "=== Disk usage BEFORE ==="
df -h / | awk 'NR==2 {print "Free space:", $4, "on /"}'

# --- targets: name -> path ---
targets=(
  "User app caches|$HOME/Library/Caches"
  "Xcode DerivedData|$HOME/Library/Developer/Xcode/DerivedData"
  "iOS Simulator caches|$HOME/Library/Developer/CoreSimulator/Caches"
  "npm cache|$HOME/.npm"
  "Generic cache|$HOME/.cache"
  "Yarn cache|$HOME/.yarn/cache"
  "pip cache|$HOME/Library/Caches/pip"
)

echo
echo "=== Sizes ==="
declare -a paths=()
for entry in "${targets[@]}"; do
  name="${entry%%|*}"
  path="${entry#*|}"
  if [ -e "$path" ]; then
    size=$(du -sh "$path" 2>/dev/null | cut -f1)
    printf "  %-22s %10s  %s\n" "$name" "$size" "$path"
    paths+=("$path")
  fi
done

echo
if [ "$EXECUTE" -eq 0 ]; then
  echo ">>> DRY RUN — nothing deleted. Re-run with --execute to actually clean. <<<"
else
  echo ">>> EXECUTING deletion of cache contents... <<<"
  for path in "${paths[@]}"; do
    if [ "$path" = "$HOME/.npm" ]; then
      # npm: use cache clean instead of raw rm for safety
      if command -v npm >/dev/null 2>&1; then
        echo "Cleaning $path via 'npm cache clean --force'"
        npm cache clean --force 2>/dev/null || echo "  (npm clean skipped)"
      fi
      continue
    fi
    if [ -d "$path" ] && [ -n "$(ls -A "$path" 2>/dev/null)" ]; then
      echo "Deleting contents of $path ..."
      rm -rf "$path"/* 2>/dev/null
    fi
  done

  # Homebrew cleanup (only if brew exists)
  if command -v brew >/dev/null 2>&1; then
    echo "Running 'brew cleanup -s' ..."
    brew cleanup -s 2>/dev/null || echo "  (brew cleanup skipped)"
  fi

  # pip cache purge
  if command -v pip3 >/dev/null 2>&1; then
    echo "Running 'pip3 cache purge' ..."
    pip3 cache purge 2>/dev/null || echo "  (pip purge skipped)"
  fi

  echo
  echo "=== Disk usage AFTER ==="
  df -h / | awk 'NR==2 {print "Free space:", $4, "on /"}'
  echo "Done."
fi
