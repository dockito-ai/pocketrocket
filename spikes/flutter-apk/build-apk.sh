#!/data/data/app.pocket/files/usr/bin/bash
# build-apk.sh <flutter build apk args...>: `flutter build apk` on the phone, inside a memory budget.
# Run it from the Flutter project. Builds for arm64 only (the phone's NDK serves no other ABI, and AGP stops at
# configuration with CXX1201 when Flutter's plugin asks for armeabi-v7a and x86_64 too), through
# flutter-dual.sh (so the Flutter tool can start gradlew), one build at a time (the shared build lock),
# with a 1.5 GB Gradle heap, 2 workers, no daemon and Kotlin compiled in-process.
# The project's own gradle.properties is not touched: -Dorg.gradle.jvmargs here wins over it.
#   build-apk.sh --debug
#   build-apk.sh --debug --flavor dev -t lib/main_dev.dart
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
lock=${PR_BUILD_LOCK:-$HOME/.cache/pr-build/build.lock}
if [ -z "${POCKETROCKET_PRELOAD:-}" ]; then
  POCKETROCKET_PRELOAD=$(flock "$lock" "$here/make-preload.sh" "${PR_PRELOAD_DIR:-$HOME/.cache/flutter-apk-spike/preload}")
fi
export POCKETROCKET_PRELOAD
export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1536m -Dorg.gradle.workers.max=2 -Dorg.gradle.daemon=false ${GRADLE_OPTS:-}"
exec flock "$lock" "$here/flutter-dual.sh" build apk --target-platform android-arm64 \
  --android-project-arg=disable-abi-filtering=true \
  --android-project-arg=kotlin.compiler.execution.strategy=in-process "$@"
