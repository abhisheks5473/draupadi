#!/usr/bin/env bash
# One-time setup. Puts the CI workflow where GitHub expects it and makes the
# Gradle wrapper executable, then you are ready to push.
set -e
cd "$(dirname "$0")"

mkdir -p .github/workflows
if [ -f workflow-build-apk.yml ]; then
  mv workflow-build-apk.yml .github/workflows/build-apk.yml
  echo "✓ workflow moved to .github/workflows/build-apk.yml"
fi
chmod +x gradlew
echo "✓ gradlew is executable"

echo
echo "Now push it:"
echo "  git init && git add -A && git commit -m 'Draupadi'"
echo "  git branch -M main"
echo "  git remote add origin https://github.com/<you>/draupadi.git"
echo "  git push -u origin main"
echo
echo "Then open the Actions tab. The APK appears in about four minutes."
