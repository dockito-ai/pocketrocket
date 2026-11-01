# Third-party icons

Settings › Tools shows each tool with its brand mark (decision 87). The marks are bundled in the APK as
VectorDrawables under `pocketrocket/src/main/res/drawable/ic_tool_*.xml`; nothing is fetched at runtime. Each is a
single-colour mark, tinted with the brand's colour and lightened or darkened only as far as needed to read on the
dark and light themes.

The logos are trademarks of their owners. PocketRocket uses them only to name the tool they stand for; it implies no
endorsement or affiliation.

| Tool | File | Source | Brand colour | Licence of the icon |
|---|---|---|---|---|
| Claude Code | `ic_tool_claude.xml` | [Simple Icons](https://simpleicons.org) 16.36.0, `claude` | `#D97757` | CC0 1.0 (Simple Icons) |
| Git | `ic_tool_git.xml` | Simple Icons 16.36.0, `git` | `#F03C2E` | CC BY 3.0 (Jason Long, per Simple Icons) |
| Node.js and npm | `ic_tool_nodedotjs.xml` | Simple Icons 16.36.0, `nodedotjs` | `#5FA04E` | CC0 1.0 (Simple Icons) |
| Dockito | `ic_tool_dockito.xml` | The "d" mark in the header of [dockito.ai](https://dockito.ai), redrawn as a vector | `#1A6DFF` | Dockito's own mark, used with Shankar's go-ahead |
| Headless Chrome | `ic_tool_googlechrome.xml` | Simple Icons 16.36.0, `googlechrome` | `#4285F4` | CC0 1.0 (Simple Icons) |
| Android, Android NDK | `ic_tool_android.xml` | Simple Icons 16.36.0, `android` | `#3DDC84` | CC BY 3.0 (Google, per Simple Icons) |
| Wrangler | `ic_tool_cloudflareworkers.xml` | Simple Icons 16.36.0, `cloudflareworkers` | `#F38020` | CC0 1.0 (Simple Icons) |
| Flutter & Dart | `ic_tool_flutter.xml` | Simple Icons 16.36.0, `flutter` | `#02569B` | CC0 1.0 (Simple Icons) |
| Rust | `ic_tool_rust.xml` | Simple Icons 16.36.0, `rust` | `#000000` | CC BY-SA 4.0 (Rust Foundation, per Simple Icons) |
| Python | `ic_tool_python.xml` | Simple Icons 16.36.0, `python` | `#3776AB` | CC0 1.0 (Simple Icons) |
| Bun | `ic_tool_bun.xml` | Simple Icons 16.36.0, `bun` | `#000000` | CC0 1.0 (Simple Icons) |
| Go | `ic_tool_go.xml` | Simple Icons 16.36.0, `go` | `#00ADD8` | CC0 1.0 (Simple Icons) |
| Deno | `ic_tool_deno.xml` | Simple Icons 16.36.0, `deno` | `#000000` | MIT (per Simple Icons) |
| Ruby | `ic_tool_ruby.xml` | Simple Icons 16.36.0, `ruby` | `#CC342D` | CC BY-SA 2.5 (per Simple Icons) |
| PHP | `ic_tool_php.xml` | Simple Icons 16.36.0, `php` | `#777BB4` | CC BY-SA 4.0 (per Simple Icons) |
| Java and Kotlin | `ic_tool_openjdk.xml` | Simple Icons 16.36.0, `openjdk` | `#000000` | BSD 3-Clause (per Simple Icons) |
| Vercel CLI | `ic_tool_vercel.xml` | Simple Icons 16.36.0, `vercel` | `#000000` | CC0 1.0 (Simple Icons) |
| Netlify CLI | `ic_tool_netlify.xml` | Simple Icons 16.36.0, `netlify` | `#00C7B7` | CC0 1.0 (Simple Icons) |
| Firebase CLI | `ic_tool_firebase.xml` | Simple Icons 16.36.0, `firebase` | `#DD2C00` | CC0 1.0 (Simple Icons) |
| flyctl | `ic_tool_flydotio.xml` | Simple Icons 16.36.0, `flydotio` | `#24175B` | CC0 1.0 (Simple Icons) |
| AWS CLI | `ic_tool_awscli.xml` | Material Icons `cloud` (Google), in AWS's orange: Simple Icons no longer carries AWS's marks | `#FF9900` | Apache 2.0 (Google) |

Simple Icons' collection is CC0; where Simple Icons records a licence for a single icon, that licence is listed above
and applies to that file. Brand guidelines: Android, Cloudflare, Flutter, Google Chrome, Node.js, Python and Rust publish
theirs, linked from Simple Icons' data (`data/simple-icons.json`).

To add one: download the SVG from `https://cdn.jsdelivr.net/npm/simple-icons@<version>/icons/<slug>.svg` into a
scratch folder, copy its single `<path d>` into a 24 × 24 VectorDrawable, give it the brand colour from Simple Icons'
data, and add a row here.
