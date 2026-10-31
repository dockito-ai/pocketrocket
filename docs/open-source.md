# Open-source readiness

31 Oct 2026. Decision 63: PocketRocket goes open source through a fresh repo. Nothing here publishes anything; Shankar runs the scan and gives the go.

## Where it stands

- **Tree cleaned** (this file's PR): Termux's leftovers are replaced or removed (see below), the private Dockito doc links are gone, `THIRD_PARTY.md` and `LICENSES/Apache-2.0.txt` are added, and the README says plainly that this is a GPLv3 fork of Termux.
- **Fresh repo built locally** at `../pocketrocket-public` (next to this repo): Termux's history up to the commit PocketRocket forked from, plus one squashed commit with the whole PocketRocket tree, authored with the noreply address. No remote is set. It is built from the state of `main` at the time; if `main` changes after that, rebuild it (see "Rebuilding the fresh repo").
- **Not done, on purpose:** no scan with an external tool, no public repo created, no visibility change, no release.

## What was cleaned

| Was | Now |
|---|---|
| `SECURITY.md` pointing at termux.dev | PocketRocket policy: report through GitHub private security advisories on the repo |
| `fastlane/metadata/` (Termux's F-Droid text and screenshots) | Removed. PocketRocket isn't on F-Droid. |
| `.github/ISSUE_TEMPLATE/*` (Termux wording) | One short bug template, one short feature template, and a config pointing security reports at advisories |
| `site/assets/sponsors/*` (Termux's sponsor logos, nothing referenced them) | Removed (the whole `site/` folder was only these) |
| `jitpack.yml` (Termux library publishing) | Removed |
| `.github/workflows/*` (7 Termux workflows: debug build, PR check, unit tests, Gradle wrapper validation, dependency submission, JitPack library builds, attach debug APKs to a release) | All removed. They are Termux's, target Termux's repo and release flow, and would run on a public repo. None serves PocketRocket today: releases are built and signed on Shankar's Mac (release skill) and tests run locally. Add a PocketRocket workflow later if wanted. |
| Dockito doc links in `docs/PROGRESS.md` and `MarkdownInlineTest.kt` | Dropped; the test uses `example.com` URLs. Generic public `dockito.ai` URLs stay, and `AGENTS.md` still says to publish docs with `dockito publish`. |
| Employer name in docs and tests, Termux `FUNDING.yml` | Already done in the previous PR |
| `docs/upstream-termux-README.md` | Kept as attribution; `README.md` links to it and to Termux |
| `app/build.gradle` `TERMUX_APP_NAME = "Termux"` placeholder | Left as is, on purpose (not referenced by the manifest; keeps upstream merges easy) |
| `.claude/skills/release/SKILL.md` | Kept, on purpose |

## Secret scan so far (by hand, before gitleaks)

Method: regexes over `git log --all -p` for API keys and tokens (Anthropic, GitHub, AWS, Slack, Google), private key headers, JWTs, generic `key/secret/token/password = "..."` strings, key and certificate files, `.env`, `.netrc`, `.npmrc`, `google-services`. No external scanner was used, and none was installed.

| Hit | Triage |
|---|---|
| `ghp_abcdefghijklmnop12345`, `sk-ant-api03-abcdefghij...`, `AKIAABCDEFGHIJKLMNOP`, `xoxb-1234-5678-abcd`, `-----BEGIN RSA PRIVATE KEY-----`, a fake JWT | Fixtures in the assistant's secret-detection tests. Obviously fake. |
| `app/testkey_untrusted.jks` and the dev keystore password in `app/build.gradle`; older history had `dev_keystore.jks` and `travis.keystore` | Termux's public upstream test keys. Not PocketRocket's. A scanner will flag them. |
| `hunter2@github.com`, `TOKEN1234@github.com`, `CLONETOKEN@git.dev` | Fixtures for URL credential stripping |
| Release keystore | Never in the repo. Its password is only in the Keychain. |

The Claude Code binary is not in the repo or the APK. Tracked binaries are the Gradle wrapper jar, fonts, icons, screenshots and Termux's test keystore.

## Scan the fresh repo with gitleaks (Shankar runs this)

Run on the fresh repo folder, not on this one. Check the installed version first with `gitleaks version`; the commands below are for v8.19 or newer. On older versions use `gitleaks detect --source . --log-opts="--all"` for the history scan and `gitleaks detect --source . --no-git` for the files.

First write an allowlist for the known test keys and fake tokens outside the repo (so the repo's tree stays identical to `main`):

```
cat > /tmp/gitleaks-pocketrocket.toml <<'TOML'
[extend]
useDefault = true

[allowlist]
description = "Known test keys and fake fixtures"
paths = [
  '''app/testkey_untrusted\.jks''',
  '''app/dev_keystore\.jks''',
  '''travis\.keystore''',
  '''gradle/wrapper/gradle-wrapper\.jar''',
]
regexes = [
  '''ghp_abcdefghijklmnop12345''',
  '''ghp_abcdefghijklmnopqrstuvwxyz0123456789''',
  '''sk-ant-api03-abcdefghij[A-Za-z0-9_\-]*''',
  '''AKIAABCDEFGHIJKLMNOP''',
  '''xoxb-1234-5678-abcd''',
  '''hunter2@github\.com''',
  '''TOKEN1234@github\.com''',
  '''CLONETOKEN@git\.dev''',
]
TOML
```

Then, first without the allowlist so nothing is hidden, then with it:

```
cd ~/Software/Shaanks/pocketrocket-public
gitleaks git --log-opts="--all" --redact -v .
gitleaks git --log-opts="--all" --redact -v -c /tmp/gitleaks-pocketrocket.toml .
gitleaks dir --redact -v -c /tmp/gitleaks-pocketrocket.toml .
```

Read every hit on the first run. Test keys, fake tokens and fixtures are expected; anything else is real until proven otherwise, and then the fresh repo must be rebuilt after the fix (the secret would otherwise still be in the squashed commit). Optionally also run `trufflehog git file://$PWD --only-verified`.

Checks on the history itself (these already passed when the repo was built):

```
git log --all --format='%ae' | sort -u        # Termux maintainers' emails and the noreply only
git rev-list --all | wc -l                    # base commits + 1
git log --all -p | grep -ci 'k[e]us'            # 0 (the brackets keep this line from matching itself)
```

## Publish steps (only after the scan is clean and Shankar says go)

*Done: the steps below are historical. The fallback to `pocketrocket-releases` has been removed (decision 83).* Decisions 65 and 66 set the order. Since the updater (`ReleaseSource`) already reads `ShankarKakumani/pocketrocket` first and falls back to `pocketrocket-releases` while that repo is private or has no release, nothing in the app, README or Settings needs to change for the new repo. The private repo currently owns the name, so it has to go first.

1. Ship the next release (1.0.5) as before, to `pocketrocket-releases`, with the release skill. This is the release that has the new updater logic.
2. Free the name `ShankarKakumani/pocketrocket`. Decision 66 says the private repo can be deleted; renaming it instead keeps an archive, and is safer if anything is unsure: `GH_TOKEN=$(gh auth token -u ShankarKakumani) gh repo rename pocketrocket-private --repo ShankarKakumani/pocketrocket --yes`. Update this Mac's clone to match: `git remote set-url origin git@github.com:ShankarKakumani/pocketrocket-private.git`. Deleting is permanent and Shankar's call.
3. Create the public repo, empty, no README or licence from GitHub: `GH_TOKEN=$(gh auth token -u ShankarKakumani) gh repo create ShankarKakumani/pocketrocket --public --description "Claude Code on an Android phone. A fork of Termux, GPLv3."`
4. Push the fresh repo: `cd ~/Software/Shaanks/pocketrocket-public && git remote add origin git@github.com:ShankarKakumani/pocketrocket.git && git push -u origin main`
5. In the repo settings: turn on private vulnerability reporting (Security, Advisories), keep Actions off, no secrets, branch protection on `main` if wanted.
6. From then on work happens in the public repo (clone it fresh; the old working copy's history shares only Termux's commits with it). Update the release skill, `docs/app-updates.md` and `AGENTS.md`, which say the code repo is private and name the private repo, in the first commit there.
7. Tag each release `vX.Y.Z` in the public repo and attach the APK there, so the source matches the APK (GPLv3). Later releases go only to the public repo; the fallback to `pocketrocket-releases` is removed from the code afterwards (decision 66), and that repo is archived with a pointer.

## Rebuilding the fresh repo

If `main` moved after the fresh repo was built, rebuild it the same way: clone the private repo's local copy into a new folder, branch from the upstream Termux base commit, replace the tree with `main`'s (`git checkout main -- .` after removing tracked files, or `git read-tree`), commit as `Shankar Kakumani <ShankarKakumani@users.noreply.github.com>`, keep only that branch, drop remotes, tags and the reflog, then `git gc --prune=now --aggressive`. The upstream base is recorded in `docs/PROGRESS.md`.

## GPL obligations in plain words

- PocketRocket is a Termux fork, so it is GPLv3 as a whole. Keep `LICENSE.md` and every upstream copyright header. Do not relicense.
- Anyone who gets the APK (including the public `pocketrocket-releases` downloads) is entitled to the matching source. The public repo is how that is met; tag each release.
- Changes to Termux files must be marked as changed. The upstream history in the fresh repo (Termux's commits up to the fork point) does most of this; the squashed commit's message says what it is.
- The Claude Code binary is not part of the work, is downloaded by the user's own phone from Anthropic, and is under Anthropic's terms, not the GPL. Keep it out of the repo and the APK.
- Dependencies under Apache 2.0, MIT and OFL are compatible with GPLv3; their notices are in `THIRD_PARTY.md`.
- The name and icon: Termux asks forks to rebrand. PocketRocket's name, package id and notification text already do.
