---
name: release
description: Cut a PocketRocket release (signed arm64 APK on GitHub) the way this project does it. Use when Shankar asks to release, ship, publish a new version or APK, bump the version, write release notes, or asks why or where a release went.
---

# Releasing PocketRocket

Releases are built, signed and published by the **Release** workflow on GitHub Actions (`.github/workflows/release.yml`, decision 67). Start it with:

```
GH_TOKEN=$(gh auth token -u ShankarKakumani) gh workflow run release.yml -R dockito-ai/pocketrocket \
  --ref main -f version=X.Y.Z -f notes="$(cat notes.md)" [-f dry_run=true]
```

Shankar usually runs `scripts/ship.sh`, which pulls, asks the version, drafts notes in $EDITOR, offers a dry run and starts and follows the workflow. `scripts/release.sh X.Y.Z --notes FILE [--dry-run]` (or `scripts/ship.sh --local`) is the local fallback when Actions can't run; it needs the keystore and the Keychain password on the Mac. Background: decisions 26, 30, 63, 65, 67 and 83 in `docs/decisions.md`, plus `docs/app-updates.md`.

## Where releases go

- **`dockito-ai/pocketrocket`**: the code repo. The workflow publishes the release here on tag `vX.Y.Z`. The updater reads it first (`ReleaseSource.REPO`) once it's public.

Releases are full releases marked Latest (decision 90), with the title `PocketRocket X.Y.Z` and the asset `pocketrocket-vX.Y.Z-arm64.apk`.

## Versioning

- The version is the single line `def pocketRocketVersion = "X.Y.Z"` in `app/build.gradle`. On main it holds the **last released** version; the workflow bumps it with `scripts/set-version.sh`. Never bump it by hand.
- versionCode = 1000 + major*10000 + minor*100 + patch. For example, 1.0.5 → 11005.
- Pick the next patch unless Shankar says otherwise. The workflow refuses a version that isn't newer or whose tag exists.

## Signing

- The release key: PKCS12, alias `pocketrocket`, RSA 4096, one password for store and key. Its certificate SHA-256 is pinned in `scripts/release-cert.sha256` (`44b223b1…1d731048`); both the workflow and `release.sh` refuse a keystore or APK signed by anything else.
- On GitHub it lives in the repo's Actions secrets `RELEASE_KEYSTORE_BASE64` and `RELEASE_KEYSTORE_PASSWORD`, which **only Shankar adds** (commands in `docs/app-updates.md`). Never add, read, print or set them yourself.
- On the Mac: `~/.pocketrocket-keys/pocketrocket-release.jks`, password in the login Keychain (service `pocketrocket-release-key`, account `pocketrocket`) and in his Passwords app, backup in iCloud Drive under `PocketRocket Keys/`. **Never print it, echo it, or write it to a file or chat.**
- Never commit the key and never sign a release with anything else: a different key forces every user to uninstall and lose their data.

## Steps

0. **Only on Shankar's go.** Release only when he explicitly says to, for this version. He often keeps pushing from the phone, so a push or a "try now" is not a go. Until he says so, check CI is green on main and report that main is ready.
1. **Sync main.** `git pull --rebase` on main. CI (`.github/workflows/ci.yml`) runs the unit tests and a debug build on every push; it must be green on the commit you release.
2. **Write the notes** in the scratchpad, not the repo. Write them for Shankar's phone: short, plain words, what's new since the last tag (`git log vPREV..HEAD --oneline`), grouped by what he will notice. Only add an uninstall warning if the signing key or package id changed. The workflow appends the code link and the `SHA-256:` line the updater checks, so don't add them.
3. **Dry run:** the command above with `-f dry_run=true`. It builds and signs (with a throwaway key if the secrets aren't set), runs every check and prints the planned commands and notes in the run summary. It never commits, tags, pushes or releases. Dry runs may use any branch; real runs only main.
4. **Real run:** the same without `dry_run`. The build job checks the version, sets it, builds and signs the arm64 APK, checks package, version and signer, hashes it and writes the notes. The publish job then checks main hasn't moved, commits `Release vX.Y.Z` as Shankar's noreply identity, tags, pushes main and the tag together, and creates the release, marked Latest. Follow it with one `gh run watch <id> -R dockito-ai/pocketrocket --exit-status`, not a polling loop.
5. **Verify** with `GH_TOKEN=$(gh auth token -u ShankarKakumani) gh release view vX.Y.Z -R <repo>` on each repo it published to. The asset digest must match the SHA-256 in the run summary.
6. **Log it.** Pull main (the workflow pushed the bump), add a line under today's date in `docs/PROGRESS.md` with the version, the versionCode and the link, then commit and push.
7. **Tell Shankar** in a few lines: the link, what's in the release, and how to get it (Settings › PocketRocket › Update, or install from the link).

## Rules

- Git identity is `Shankar Kakumani <ShankarKakumani@users.noreply.github.com>`. Add no Co-Authored-By or AI attribution lines to commits or release notes.
- Never bundle Claude Code in the APK. It's downloaded at first run.
- Keep personal info out of release notes and the APK: no emails, Mac paths or other projects' names.
- The package id is `app.pocket` (decision 36). Don't change it; a different id is a different app and won't update.
- If a run fails after the push but before the release exists, don't rerun it: the version is already bumped and tagged. Download the run's `release` artifact (`gh run download <id> -n release`) and create the missing release by hand with the `gh release create` line from the run summary (`--verify-tag` on the code repo).
- Per the global CLAUDE.md, a long release can go to a Sonnet agent. Name the model on the spawn.
