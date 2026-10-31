# App updates (S3, decisions 26, 30, 66 and 67)

PocketRocket updates itself from a button in Settings. It never checks, downloads or installs anything on its own.

Code: `pocketrocket/src/main/java/com/pocketrocket/appupdate/` (`AppRelease.kt` parsing and hashes, `ReleaseSource.kt` where releases come from, `AppUpdater.kt` state and flow, `ApkInstaller.kt` PackageInstaller), `ui/settings/AppUpdateScreen.kt`, the "PocketRocket" row in `SettingsScreen.kt`, `scripts/release.sh`.

## Version

`app/build.gradle` has `def pocketRocketVersion = "X.Y.Z"`; versionName is that and

    versionCode = 1000 + major*10000 + minor*100 + patch      (0.1.1 → 1101, 1.2.3 → 11203)

Minor and patch stay below 100. Every code is above Termux's 118 and grows with the version. `validateVersionName` still runs. Only a release changes the line, through `scripts/set-version.sh` (the Release workflow, or `scripts/release.sh` locally).

## Where releases come from

The updater reads **`ShankarKakumani/pocketrocket`** (`ReleaseSource.REPO`) first and falls back to the old public **`ShankarKakumani/pocketrocket-releases`** (`OLD_REPO`) while the code repo is private or has no release (decision 66). The app reads `https://api.github.com/repos/<repo>/releases` over plain HTTPS, with no login and no gh. It picks the highest `vX.Y.Z` tag by semver, prereleases included and drafts skipped, and downloads the arm64 asset's `browser_download_url`. Each release is tagged `vX.Y.Z` in the code repo, so a release maps to a commit. The Release workflow publishes every release to the code repo, and to `pocketrocket-releases` as well while the `RELEASES_REPO_TOKEN` secret is set (decision 67).

## The screen

Settings shows **PocketRocket · 0.1.1**. If the last check found a newer version, it adds "· update available" in the accent colour. That is a cached result, with no network call from Settings. Tapping the row opens the PocketRocket screen, which:

- checks once when it opens, and again on **Check again**
- shows the installed and latest version, where it looked, and any error ("No PocketRocket releases yet.", rate limit, offline)
- shows the release notes with the chat's MarkdownText
- has **Update to X** and a **View on GitHub** link

**Update to X** works like this:
1. **Sessions.** If any session is working, starting or waiting on a permission, it asks first ("2 sessions are working. They pause during the update and resume after.") with Update anyway / Wait.
2. **Install unknown apps.** If this isn't allowed for PocketRocket, it opens that system setting. The update continues when you come back with it allowed.
3. **Download.** The APK goes to `cache/app-update/`, with a progress bar.
4. **SHA-256.** The download must match the asset's `digest` from the API. If there is no digest, it must match the `SHA-256:` line in the notes. If both exist and disagree, if neither exists, or if the hash doesn't match, nothing is installed and the file is deleted.
5. **Package check.** The APK must be `app.pocket` with a higher versionCode than the installed app.
6. **Install.** It goes through a PackageInstaller session. Android shows its own "Do you want to update this app?" and replaces the app, which ends the process. Reopen PocketRocket: sessions are Paused and resume when opened, as after any restart.

## Signing

Releases are signed with PocketRocket's own release key, not the debug key:
- **Keystore:** `~/.pocketrocket-keys/pocketrocket-release.jks` (PKCS12, alias `pocketrocket`).
- **Password:** one password for both the store and the key. It lives in the macOS login Keychain (service `pocketrocket-release-key`, account `pocketrocket`).
- **Certificate:** its SHA-256 is pinned in `scripts/release-cert.sha256` (`44b223b1bfa74b64481318e5b828ac212d5cd7b08f836f3387befd3c1d731048`). The workflow and `release.sh` refuse a keystore whose certificate isn't the pin, and an APK with any other signer (`scripts/check-release-apk.sh`).
- **On GitHub:** the same keystore and password in the repo's Actions secrets (below), added by Shankar only.

`app/build.gradle` has a `release` signingConfig that exists only when `POCKETROCKET_KEYSTORE` and `POCKETROCKET_KEY_PASSWORD` (and optionally `POCKETROCKET_KEY_ALIAS`) are set. Nothing secret is in the repo. Debug builds are unchanged and keep Termux's `testkey_untrusted.jks`. Release builds keep Termux's `minifyEnabled true`. The minified, release-signed build was checked on the emulator: setup, Settings, and the update screen against the real releases repo.

**Switching keys once.** The first release-key build can't install over an app signed with the old debug key, because Android refuses an update from a different signer. That takes one uninstall, which wipes the app's data, and then a fresh setup and sign-in. Every update after that installs in place and keeps data.

**Back up the key.** If the key is lost, no future build can update the installed app, and you are back to uninstalling and setting up again. Keep:
- the copy of `pocketrocket-release.jks` in iCloud Drive › **PocketRocket Keys/**
- the password in the Passwords app (the Keychain item is only on this Mac)

To restore on a new Mac, copy the jks to `~/.pocketrocket-keys/` and add the Keychain item:

    security add-generic-password -s pocketrocket-release-key -a pocketrocket -w

## CI and releasing (GitHub Actions, decision 67)

**CI** (`.github/workflows/ci.yml`) runs on every PR and every push to main: the unit tests of `:pocketrocket`, `:terminal-emulator` and `:app`, then `:app:assembleDebug`. The debug arm64 APK is kept as the run's `pocketrocket-debug-arm64` artifact for 7 days; test reports are uploaded when something fails. Shared setup is in `.github/actions/android-setup` (JDK 17, the NDK named in `gradle.properties`, Gradle with its cache). Nothing outside git is needed: the runner's Android SDK replaces `local.properties`, and Termux's bootstrap zips are fetched by the build itself (`downloadBootstraps` in `app/build.gradle`, each checked against its pinned SHA-256), then cached between runs keyed on those pins. Gradle re-checks a cached zip and downloads it again if it doesn't match.

**Release** (`.github/workflows/release.yml`) is started by hand, from main:

    scripts/ship.sh                       # asks version and notes, offers a dry run, starts and follows the workflow
    GH_TOKEN=$(gh auth token -u ShankarKakumani) gh workflow run release.yml -R ShankarKakumani/pocketrocket \
      --ref main -f version=1.0.5 -f notes="$(cat notes.md)" [-f dry_run=true]

Inputs: `version` (X.Y.Z, newer than `app/build.gradle`), `notes` (Markdown; the code link and SHA-256 line are added) and `dry_run`. One release runs at a time. The **build** job (read-only token):
1. Refuses a real run from anything but main, a version that isn't X.Y.Z with minor and patch below 100 or isn't newer, and a tag that already exists.
2. Sets the version (`scripts/set-version.sh`).
3. Decodes the keystore secret into `$RUNNER_TEMP` and refuses it unless its certificate is the pin in `scripts/release-cert.sha256`. The password never reaches the log.
4. Runs `assembleRelease` with arm64 splits.
5. `scripts/check-release-apk.sh`: aapt2 says `app.pocket`, the versionName and versionCode; apksigner lists the pinned certificate as the only signer.
6. Names it `pocketrocket-vX.Y.Z-arm64.apk`, computes its SHA-256 and writes the notes ending in ``SHA-256: `<hex>` ``, the line the updater reads.
7. Prints the plan and the notes in the run summary, keeps APK and notes as the run's `release` artifact, and deletes the keystore.

The **publish** job (the only one with `contents: write`, real runs from main only) checks main hasn't moved since the build and the APK's hash is unchanged, commits "Release vX.Y.Z" as `Shankar Kakumani <ShankarKakumani@users.noreply.github.com>`, tags `vX.Y.Z`, pushes main and the tag together, and creates the prerelease "PocketRocket X.Y.Z" in this repo with the APK and notes, checking GitHub's asset digest against the hash. With `RELEASES_REPO_TOKEN` set it creates the same prerelease in `pocketrocket-releases` (the updater's fallback, decision 66); without it that step is skipped.

**Dry run** (`dry_run=true`, any branch): the build job only. It signs with the real key when the secrets are there, else with a throwaway key made in the job (the signer is then checked against that key), runs every check and prints what would be published. It never commits, tags, pushes or releases.

### Secrets (Shankar adds them; never through chat)

Settings › Secrets and variables › Actions on `ShankarKakumani/pocketrocket`, or from the Mac without printing anything:

    export GH_TOKEN=$(gh auth token -u ShankarKakumani)
    base64 -i ~/.pocketrocket-keys/pocketrocket-release.jks | gh secret set RELEASE_KEYSTORE_BASE64 -R ShankarKakumani/pocketrocket
    security find-generic-password -s pocketrocket-release-key -a pocketrocket -w | tr -d '\n' | gh secret set RELEASE_KEYSTORE_PASSWORD -R ShankarKakumani/pocketrocket

- `RELEASE_KEYSTORE_BASE64`: the PKCS12 keystore, base64. Alias `pocketrocket`.
- `RELEASE_KEYSTORE_PASSWORD`: its one password (store and key).
- `RELEASES_REPO_TOKEN` (optional, only while the code repo is private): a fine-grained token limited to `ShankarKakumani/pocketrocket-releases` with Contents read and write, made at github.com › Settings › Developer settings › Fine-grained tokens. `gh secret set RELEASES_REPO_TOKEN -R ShankarKakumani/pocketrocket` asks for it without echoing. Delete the secret and the token once the public repo is up.

Actions must be enabled on the repo (Settings › Actions › General › Allow all actions). Secrets don't move with the code: when the public repo replaces the private one (decisions 63 and 66), enable Actions there and run the first two commands again against it.

### Local fallback

`scripts/release.sh X.Y.Z [--notes FILE] [--dry-run]` (or `scripts/ship.sh --local`) does the same on the Mac when Actions can't run: it checks main is clean and equal to origin/main and git's email is the noreply one, reads the keystore and the Keychain password (never printed), checks the key against the pin, and runs the same `set-version.sh` and `check-release-apk.sh`. It publishes to `pocketrocket-releases` and then the code repo (`--verify-tag`) with `GH_TOKEN=$(gh auth token -u ShankarKakumani)`. Its dry run puts `app/build.gradle` back and never commits, pushes or calls GitHub.

## Testing without GitHub (debug builds only)

A **debuggable** build reads a fake release source from `<dataDir>/app-update-test/`, which is `/data/data/app.pocket/app-update-test/`. It holds a `releases.json` in GitHub's format plus the APKs it names. The screen labels it "Test release source", so it can't pass for GitHub. Release builds aren't debuggable, so they never read it, and `run-as` can't write there.

    adb push releases.json pocketrocket-vX.Y.Z-arm64.apk /data/local/tmp/
    adb shell run-as app.pocket mkdir -p app-update-test
    adb shell run-as app.pocket cp /data/local/tmp/releases.json /data/local/tmp/pocketrocket-vX.Y.Z-arm64.apk app-update-test/

Delete the folder to go back to GitHub.

## Verified on the emulator (29 Oct 2026, API 36 arm64)

- Debug 0.1.1 (versionCode 1101) against a fake source listing a draft v0.9.0, v0.1.2 and v0.1.0: it showed "0.1.2 is out" (the draft was skipped), the notes, and Update to 0.1.2.
- **Wrong digest:** Update first opened "Install unknown apps". After allowing it and pressing back, it continued and refused: "the download's SHA-256 (52ce…) doesn't match GitHub's digest (0000…)". The cached APK was deleted.
- **Correct digest, with a session mid-turn:** the warning "1 session is working…" appeared, then Update anyway, then Android's "Do you want to update this app?", then Update. The installed app is now 0.1.2 / 1102. Reopened: the session was Paused, and opening it resumed the conversation with the full reply.
- **Fake v0.1.3 carrying the 1102 APK:** refused with "the APK's versionCode 1102 isn't newer". The Settings row showed "0.1.2 · update available" in accent, and still did after a force-stop and reopen (cached).
- **Real source:** with the test folder removed, it read `pocketrocket-releases` over HTTPS and said "No PocketRocket releases yet." (the repo is empty).
- **Release-signed 0.1.2** (from the `release.sh` dry run, minified): it installed after uninstalling the debug app. Setup ran to the end, then Settings and the PocketRocket screen against the real repo, with no crashes.
- **Unit tests:** `AppReleasesTest` covers tag parsing, semver order, latest pick, digest vs notes, conflicts and missing hashes.

Not tested: the download progress bar over real HTTPS, because the releases repo has no release yet and a local copy finishes too fast to see it. A release-key-to-release-key update has not been run either. It is the same flow as the debug-to-debug update above.
