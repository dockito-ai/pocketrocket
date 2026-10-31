| Tool | Group | Verdict | Cause | Source | Download | Installed | Install | Probe | Notes |
|---|---|---|---|---|---|---|---|---|---|
| sqlite3 | Databases | works | - | termux pkg | 663 KB | 2 MB | 4s | 1s |  |
| fd | Utilities | works | - | termux pkg | 935 KB | 3 MB | 3s | 0s |  |
| yq | Utilities | works | - | termux pkg | 4 MB | 14 MB | 4s | 1s |  |
| pnpm | JS tooling | works | - | termux pkg | 13 MB | 45 MB | 14s | 1s |  |
| bun | Languages | works | - | termux pkg | 24 MB | 85 MB | 9s | 0s |  |
| tsc | JS tooling | fails | D | npm | 18 MB | 4 MB | 6s | 0s | also A under plain shell;             throw new Error("Unable to resolve " + platformPackageName + ". Either your platform is unsupported, or you are missing the package on disk."); Error: Unable to resolve @typescript/typescript-android-arm64. E |
| eslint | JS tooling | works-with-fix | A | npm | 14 MB | 15 MB | 11s | 1s | plain: sh: 1: eslint: not found  |
| make | Utilities | works | - | termux pkg | 0 KB | - | 0s | 0s |  (preinstalled) |
| rust-wasm | Known gaps | works | - | termux pkg | 0 KB | - | 0s | 0s |  (preinstalled) |
