#!/bin/zsh -l
# Double-click in Finder to start the Self-Healing desktop app (opens in Terminal).
cd "$(dirname "$0")" || exit 1
./gradlew :desktop:run --args="$PWD/config.yml"
