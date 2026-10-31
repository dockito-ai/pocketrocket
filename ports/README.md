# Ports

Some tools PocketRocket offers have no build that runs on Android. When the fix is building upstream's own source a
little differently, the build lives here instead of in a repository of its own.

A port is:

- `ports/<name>/README.md`: why it's needed, what the build changes, how the app uses it, how to build a new version.
- `.github/workflows/port-<name>.yml`: a manual workflow that checks out upstream's source at a version, builds it on
  GitHub's runners, and publishes a release tagged `<name>-v<version>`. GitHub only runs workflows from
  `.github/workflows`, so the workflow can't sit next to the README.

Rules:

- Upstream source unmodified where possible; build flags first, patches only when there's no flag, kept in the
  port's folder and listed in its README.
- Releases are tagged `<name>-v<version>` and not marked latest, so they never look like an app release (the app's
  updater only reads `vX.Y.Z` tags).
- Nothing from a port goes into the APK. The setup script downloads the release when the user installs the tool,
  and checks it against a SHA-256 pinned in the script.
- Each release includes upstream's licence, and `THIRD_PARTY.md` lists the port.

| Port | For | Why |
|---|---|---|
| [workerd](workerd/README.md) | Wrangler tool, `wrangler dev` | Stock build uses tcmalloc, which needs a 48-bit address space; Android gives apps 39 bits |
