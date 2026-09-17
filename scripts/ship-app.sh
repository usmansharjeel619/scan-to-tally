#!/usr/bin/env bash
#
# Builds and publishes the handset app, bumping the version as it goes.
#
# The bump is part of shipping, not a separate act of discipline. Four changes
# once went out as 1.1.0 because bumping was something to remember, and a
# version that does not move tells nobody which build they are holding.
#
#   ./scripts/ship-app.sh            -> 1.1.3 becomes 1.1.4
#   ./scripts/ship-app.sh minor      -> 1.1.3 becomes 1.2.0
#   ./scripts/ship-app.sh major      -> 1.1.3 becomes 2.0.0
#
set -euo pipefail
cd "$(dirname "$0")/.."

PART="${1:-patch}"
BUILD_HOST="root@BUILD_HOST_REDACTED"
SSH_OPTS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=15 -o LogLevel=ERROR"
: "${SSHPASS:?set SSHPASS before running}"

current="$(tr -d '[:space:]' < VERSION)"
IFS=. read -r major minor patch <<< "$current"

case "$PART" in
  major) major=$((major + 1)); minor=0; patch=0 ;;
  minor) minor=$((minor + 1)); patch=0 ;;
  patch) patch=$((patch + 1)) ;;
  *) echo "usage: $0 [patch|minor|major]" >&2; exit 2 ;;
esac

next="${major}.${minor}.${patch}"
echo "$next" > VERSION
echo "version ${current} -> ${next}"

sshpass -e rsync -az --delete -e "ssh $SSH_OPTS" \
  --exclude build --exclude .gradle \
  android/app/src android/app/build.gradle.kts "$BUILD_HOST:/opt/stt-build/src/android/app/"
sshpass -e rsync -az -e "ssh $SSH_OPTS" VERSION contracts "$BUILD_HOST:/opt/stt-build/src/"

sshpass -e ssh $SSH_OPTS "$BUILD_HOST" \
  '. /opt/stt-build/env.sh && cd /opt/stt-build/src/android &&
   nice -n 19 ./gradlew --no-daemon --console=plain assembleDebug testDebugUnitTest 2>&1 |
     grep -E "^(e:|BUILD|FAILURE)" | head -10'

# The version file the relay serves the APK under. Written with the binary, so
# the two can never disagree about what was published.
sshpass -e rsync -az -e "ssh $SSH_OPTS" VERSION "$BUILD_HOST:/opt/scan-to-tally/dist/app.version"
sshpass -e ssh $SSH_OPTS "$BUILD_HOST" \
  'cp /opt/stt-build/src/android/app/build/outputs/apk/debug/app-debug.apk /opt/scan-to-tally/dist/app.apk'

echo
echo "published:"
sshpass -e ssh $SSH_OPTS "$BUILD_HOST" \
  'cd /tmp && rm -rf shipchk && mkdir shipchk && cd shipchk &&
   unzip -qo /opt/scan-to-tally/dist/app.apk "classes*.dex" &&
   printf "  %s\n" "$(grep -ah -o "Scan to Tally v[0-9.]*" classes*.dex | head -1)" &&
   printf "  build %s\n" "$(grep -ah -oE "[0-9]+ [A-Z][a-z]+ [0-9]{2}:[0-9]{2}" classes*.dex | sort -u | head -1)" &&
   cd /tmp && rm -rf shipchk'

curl -sSI "https://relay.example.com/dl/DOWNLOAD_PATH_REDACTED/app.apk" |
  grep -i "content-disposition" | sed 's/^/  /'
