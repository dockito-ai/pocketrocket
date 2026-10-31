package com.pocketrocket.relocate

import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Moves Termux's packages from `/data/data/com.termux` to our own id (decision 36) without rebuilding them.
 *
 * Termux's bootstrap and every package from its mirrors are built for `/data/data/com.termux`. Our id is exactly as
 * long, so the path is swapped byte for byte: string lengths, ELF offsets and tar sizes all stay valid. It runs in
 * two places:
 *  - the bootstrap, as TermuxInstaller extracts it ([rewrite] on every file and symlink target);
 *  - every .deb apt installs later, through an apt `DPkg::Pre-Install-Pkgs` hook ([installAptHook]) that runs after
 *    apt has checked the package against the signed index and before dpkg unpacks it. It covers `pkg`/`apt` typed in
 *    a terminal and everything the app installs, since both go through apt.
 */
object PrefixRelocation {
    private const val TERMUX_ID = "com.termux"
    private val ownId = TermuxConstants.TERMUX_PACKAGE_NAME

    /** Same-length swaps: the data dir (also without its leading slash, as in dpkg md5sums), the per-app dir under
     *  files/apps, and the app component and package name that Termux's scripts pass to `am` (the classes stay in
     *  the com.termux namespace). */
    private val rules: List<Pair<String, String>> = listOf(
        "data/data/$TERMUX_ID" to "data/data/$ownId",
        "user/0/$TERMUX_ID" to "user/0/$ownId",
        "files/apps/$TERMUX_ID" to "files/apps/$ownId", // termux-am's socket dir, named after the app id

        "$TERMUX_ID/$TERMUX_ID." to "$ownId/$TERMUX_ID.",
        "\"$TERMUX_ID\"" to "\"$ownId\"",
    )

    /** False when the app is built as com.termux itself, or the ids differ in length (then nothing can be swapped). */
    @JvmStatic
    val needed: Boolean = ownId != TERMUX_ID && ownId.length == TERMUX_ID.length

    private val byteRules = rules.map { (from, to) -> from.toByteArray() to to.toByteArray() }

    /** Swaps every rule in `bytes[0 until length]` in place. Returns whether anything changed. */
    @JvmStatic
    fun rewrite(bytes: ByteArray, length: Int): Boolean {
        if (!needed) return false
        var changed = false
        for ((from, to) in byteRules) {
            val first = from[0]
            var i = 0
            val last = length - from.size
            while (i <= last) {
                if (bytes[i] == first && matches(bytes, i, from)) {
                    to.copyInto(bytes, i)
                    changed = true
                    i += from.size
                } else i++
            }
        }
        return changed
    }

    /** The same swaps on a string, e.g. a symlink target. */
    @JvmStatic
    fun rewrite(text: String): String =
        if (!needed) text else rules.fold(text) { acc, (from, to) -> acc.replace(from, to) }

    private fun matches(bytes: ByteArray, at: Int, pattern: ByteArray): Boolean {
        for (j in pattern.indices) if (bytes[at + j] != pattern[j]) return false
        return true
    }

    /**
     * Writes the apt hook into [prefix] (the real or the staging $PREFIX): the script at
     * `libexec/pocketrocket/relocate-debs` and `etc/apt/apt.conf.d/99pocketrocket-relocate` pointing at it.
     * Safe to call on every start; files are only rewritten when they differ. Neither file belongs to a package, so
     * upgrades of apt or dpkg leave them alone.
     */
    @JvmStatic
    fun installAptHook(prefix: File) {
        if (!needed) return
        val realPrefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        val script = File(prefix, SCRIPT)
        writeIfChanged(script, hookScript(realPrefix))
        script.setExecutable(true, true)
        writeIfChanged(
            File(prefix, CONF),
            "// PocketRocket (decision 36): rewrite Termux's paths in every package before dpkg unpacks it.\n" +
                "DPkg::Pre-Install-Pkgs:: \"$realPrefix/$SCRIPT\";\n",
        )
    }

    private const val SCRIPT = "libexec/pocketrocket/relocate-debs"
    private const val CONF = "etc/apt/apt.conf.d/99pocketrocket-relocate"

    internal fun writeIfChanged(file: File, text: String) {
        if (file.isFile && file.readText() == text) return
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { tmp.delete(); error("could not write $file") }
    }

    private fun sedQuote(s: String) = s.replace("\\", "\\\\").replace(".", "\\.").replace("|", "\\|").replace("'", "'\\''")

    /** The environment variable naming the hook's progress file; the setup script sets it, a terminal does not. */
    const val PROGRESS_ENV = "POCKETROCKET_RELOCATE_PROGRESS"

    /** The hook: apt passes the .deb paths on stdin (protocol version 1, one per line). Each is unpacked with
     *  dpkg-deb, its file names, contents and symlink targets swapped, and rebuilt uncompressed in place. Packages
     *  are done in parallel, one per CPU (the script runs itself with `--one <deb>` for each). When [PROGRESS_ENV]
     *  names a file, the hook appends `start <count>`, then `done <file name>` as each package is ready, which the
     *  setup script shows as "Preparing packages (12 of 47)". */
    internal fun hookScript(prefix: String): String {
        val sed = rules.joinToString(" ") { (from, to) -> "-e 's|${sedQuote(from)}|${to.replace("'", "'\\''")}|g'" }
        val grep = rules.joinToString(" ") { (from, _) -> "-e '${from.replace("'", "'\\''")}'" }
        // Only links whose target holds one of the paths. Most links are relative, and a readlink and sed for every
        // link made packages full of man-page links (openssl-glibc, ncurses-glibc) take up to 45 s each.
        val lname = rules.joinToString(" -o ") { (from, _) -> "-lname '*${from.replace("'", "'\\''")}*'" }
        return """
            |#!$prefix/bin/bash
            |# PocketRocket apt hook, written by the app (PrefixRelocation.kt); edits here are overwritten.
            |# Swaps /data/data/$TERMUX_ID for /data/data/$ownId in each .deb apt is about to install.
            |set -Eeo pipefail
            |trap 'echo "PocketRocket: could not relocate ${'$'}{deb:-a package} (line ${'$'}LINENO)" >&2' ERR
            |export LC_ALL=C
            |unset LD_PRELOAD
            |
            |report() {
            |  if [ -n "${'$'}{$PROGRESS_ENV:-}" ]; then printf '%s\n' "${'$'}1" >>"${'$'}$PROGRESS_ENV" 2>/dev/null || true; fi
            |}
            |
            |relocate() {
            |  deb=${'$'}1
            |  report "begin ${'$'}{deb##*/}"
            |  work=${'$'}(mktemp -d "${'$'}{TMPDIR:-$prefix/tmp}/relocate-deb.XXXXXX")
            |  dpkg-deb -R "${'$'}deb" "${'$'}work/root"
            |  if [ -d "${'$'}work/root/data/data/$TERMUX_ID" ]; then
            |    mv "${'$'}work/root/data/data/$TERMUX_ID" "${'$'}work/root/data/data/$ownId"
            |  fi
            |  find "${'$'}work/root" -type l \( $lname \) -print0 | while IFS= read -r -d '' link; do
            |    target=${'$'}(readlink "${'$'}link")
            |    new=${'$'}(printf '%s' "${'$'}target" | sed $sed)
            |    [ "${'$'}new" = "${'$'}target" ] || ln -sfn "${'$'}new" "${'$'}link"
            |  done
            |  { grep -rlaZF $grep "${'$'}work/root" || [ ${'$'}? -eq 1 ]; } | xargs -0 -r sed -i $sed
            |  dpkg-deb --nocheck --root-owner-group -Znone -b "${'$'}work/root" "${'$'}work/new.deb" \
            |    2> >(grep -v 'not checking contents of control area' >&2 || true) >/dev/null
            |  mv -f "${'$'}work/new.deb" "${'$'}deb"
            |  rm -rf "${'$'}work"
            |  report "done ${'$'}{deb##*/}"
            |}
            |
            |if [ "${'$'}{1:-}" = --one ]; then
            |  relocate "${'$'}2"
            |  exit 0
            |fi
            |
            |debs=()
            |while IFS= read -r line; do
            |  case "${'$'}line" in
            |    *.deb) if [ -f "${'$'}line" ]; then debs+=("${'$'}line"); fi ;;
            |  esac
            |done
            |if [ ${'$'}{#debs[@]} -eq 0 ]; then exit 0; fi
            |report "start ${'$'}{#debs[@]}"
            |jobs=${'$'}(nproc 2>/dev/null || echo 2)
            |printf '%s\0' "${'$'}{debs[@]}" | xargs -0 -r -n 1 -P "${'$'}jobs" "${'$'}0" --one
            |""".trimMargin()
    }
}
