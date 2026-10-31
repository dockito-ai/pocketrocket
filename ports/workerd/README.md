# Port: workerd

[workerd](https://github.com/cloudflare/workerd) is Cloudflare's Workers runtime. Wrangler runs it for `wrangler dev`,
which serves a Worker on `localhost:8787` with KV, D1, R2 and Durable Objects stored locally. This port is what makes
local Workers dev work in PocketRocket (decision 82).

## Why we built it

Wrangler's Settings → Tools tile first shipped without local dev (decision 49): Wrangler could log in and deploy, but
`wrangler dev` couldn't run, so the only way to try a change was to deploy it. Shankar wanted one Wrangler tool that
does it all.

workerd has no Android build. npm publishes `@cloudflare/workerd-linux-arm64`, and Android phones are arm64 Linux, so
we ran that through the glibc loader PocketRocket already uses for Claude Code. It aborted at once:

```
MmapAligned() failed ... assumes a 48-bit virtual address space
```

That comes from tcmalloc, the allocator workerd is built with. tcmalloc tags memory using address bits only a 48-bit
virtual address space has. Android kernels give apps 39 bits. proot or other wrappers can't change the kernel's address
space, so the binary itself had to change.

workerd's build has a switch for exactly this: `--//src/workerd/server:use_tcmalloc=False` uses the system allocator
instead. Building with it is the whole port. The source is unmodified upstream at the tag Wrangler pins.

A workerd build is far too big for a phone (Bazel, V8, about 15,000 compile steps, several GB of disk), so it runs on
GitHub's free arm64 runners and takes about 1.5 hours.

## How the app uses it

The Wrangler tool's install (`setup/pocketrocket-setup.sh --only wrangler`):

1. Installs Wrangler from npm at its pinned version with install scripts off.
2. Downloads `workerd-linux-arm64-notcmalloc-<version>.tar.gz` from this repository's `workerd-v<version>` release and
   checks it against `WORKERD_SHA256` in the script.
3. Puts the binary in Wrangler's `node_modules/workerd/lib/workerd-glibc` and points workerd's loader at a wrapper,
   `workerd-android`, which runs it through the glibc loader without `LD_PRELOAD` (termux-exec is bionic) and with the
   phone's CA certificates (glibc looks in `/etc/ssl`).
4. Checks it: `wrangler deploy --dry-run`, then `wrangler dev` answering one request.

Checked on a phone with a 39-bit address space and 4K pages: requests, outbound `fetch`, KV, D1, R2 and Durable Objects.

## Bumping Wrangler

A new Wrangler usually pins a new workerd, and the install refuses a workerd it has no build for. So:

1. Find the workerd version: `npm view wrangler@<version> dependencies.workerd`.
2. Run **Port: workerd** (Actions → Run workflow) with that version. About 1.5 hours; it publishes release
   `workerd-v<version>` and keeps the tarball as a 30-day artifact too.
3. In the setup script, update `WRANGLER_VERSION`, `WORKERD_VERSION`, `WORKERD_SHA256` (from the release's `.sha256`)
   and `WORKERD_BYTES`; in `WranglerTool.kt`, `VERSION` and `WORKERD_VERSION`.

The workflow keeps a Bazel disk cache between runs, which shares the repository's GitHub cache space with CI.

## Licence

workerd is © Cloudflare, Inc., Apache-2.0. Each release includes upstream's `LICENSE`. Not affiliated with or endorsed
by Cloudflare.
