package com.pocketrocket.pkgtools

import com.pocketrocket.clilogin.LoginSpec
import com.pocketrocket.clitools.AwsLogin
import com.pocketrocket.clitools.FlyLogin
import com.pocketrocket.termux.DpkgStatus

/** Java 17's package; the enum's entries can't read their companion while they're built. */
private const val OPENJDK_17 = "openjdk-17"

/** Another package that needs part of a tool, so that part stays on uninstall: Headless Chrome needs python and pip. */
data class HeldBy(val pkg: String, val name: String, val keeps: List<String>)

/**
 * Variables a tool needs in every shell (decision 98). The install writes them to [file] in `$PREFIX/etc/profile.d`
 * for terminals; Claude sessions get them from the app while [binary] is in `$PREFIX/bin`, however it got there.
 * [label] is how the tile names the file when it's missing ("gems setting missing").
 */
data class ShellSetting(val file: String, val binary: String, val environment: Map<String, String>, val label: String)

/**
 * What the phone says beyond dpkg, read with it: the profile.d files there, where `$PREFIX/bin/java` leads, and the
 * JDKs Gradle should see against what `~/.gradle/gradle.properties` lists ([GradleJdks]).
 */
data class PackageDisk(
    val profiles: Set<String> = emptySet(),
    val javaDefault: String? = null,
    val gradleJdks: GradleJdks = GradleJdks(),
)

/** A job other than install and remove: Java's Repair sets java and Gradle's JDK list without touching packages. */
data class ToolRepair(val option: String, val step: String)

/**
 * Add-ons that are only Termux packages (decision 92): Python with pip and uv, Bun, and Rust's wasm target (an option
 * on the Rust page); and the languages Go, Deno, Ruby, PHP, and Java with Kotlin (decision 98). The setup script's
 * `--only <option>` / `<option>-remove` installs and removes them and holds the same package lists
 * (`pkg_tool_packages`). What is installed is read from dpkg's status each time, never from a flag the app saved, so
 * a `pkg install` or `pkg uninstall` in a terminal shows on the tile too. Sizes are apt's, measured on the phone
 * (docs/tool-sweep.md).
 */
enum class PackageTool(
    val option: String,
    /** What the steps call it: "Installing Python, pip and uv". */
    val title: String,
    /** Its packages and the name each version shows under; the first is the one that means "installed". */
    val packages: List<Pair<String, String>>,
    val downloadMb: Int,
    val installedMb: Int,
    val heldBy: HeldBy? = null,
    val shell: ShellSetting? = null,
    /** Other packages its state depends on (Java 17, for Java's default). */
    val alsoWatched: List<String> = emptyList(),
    /** Its own login, with Log in and Log out on its page (the deploy CLIs, decision 98). */
    val login: LoginSpec? = null,
    /**
     * Packages its install doesn't add but its page does (Java 25): they mean it's on the phone too, show with its
     * versions and size, and its Uninstall takes them. The script's `pkg_tool_packages` lists only [packages].
     */
    val extras: List<Pair<String, String>> = emptyList(),
    /** What Repair runs instead of the install, or null for the install. */
    val repair: ToolRepair? = null,
) {
    Python(
        "python", "Python, pip and uv", listOf("python" to "Python", "python-pip" to "pip", "uv" to "uv"),
        downloadMb = 16, installedMb = 67,
        heldBy = HeldBy("chromium", "Headless Chrome", listOf("python", "python-pip")),
    ),
    Bun("bun", "Bun", listOf("bun" to "Bun"), downloadMb = 25, installedMb = 85),
    RustWasm("rust-wasm", "the wasm target", listOf("rust-std-wasm32-unknown-unknown" to "wasm32-unknown-unknown"), downloadMb = 21, installedMb = 90),

    // The language tiles (decision 98), sized on the phone ("Tested for tiles" in docs/tool-sweep.md).
    Go("go", "Go", listOf("golang" to "Go"), downloadMb = 36, installedMb = 218),
    Deno("deno", "Deno", listOf("deno" to "Deno"), downloadMb = 26, installedMb = 93),
    Ruby(
        "ruby", "Ruby", listOf("ruby" to "Ruby"), downloadMb = 9, installedMb = 45,
        // Termux's ruby doesn't load RubyGems by itself, so `require` of an installed gem fails without it.
        shell = ShellSetting("pocketrocket-ruby.sh", "ruby", mapOf("RUBYOPT" to "-rrubygems"), "gems setting"),
    ),
    /** Not composer: Termux's package fails its phar signature check once the prefix is rewritten. */
    Php("php", "PHP", listOf("php" to "PHP"), downloadMb = 13, installedMb = 101),
    /**
     * Install puts in Java 21 and Kotlin (kotlin needs openjdk-21); the page adds and removes 17, 21, 25 and Kotlin on
     * their own ([JAVA_PARTS], decision 101), so Java 25 alone is the tile too. Uninstall takes 21, 25 and Kotlin.
     * Java 17 (the Android tool's) is never removed here and stays the default `java` while it is on the phone.
     */
    Java(
        "java", "Java 21 and Kotlin", listOf("openjdk-21" to "Java 21", "kotlin" to "Kotlin"),
        downloadMb = 196, installedMb = 350,
        alsoWatched = listOf(OPENJDK_17), extras = listOf("openjdk-25" to "Java 25"),
        repair = ToolRepair("java-setup", "Setting up Java"),
    ),

    // The deploy CLIs that are Termux packages (decision 98); the npm ones are NpmTool.
    Aws("aws", "the AWS CLI", listOf("awscli" to "AWS CLI"), downloadMb = 36, installedMb = 204, login = AwsLogin),
    Fly("flyctl", "flyctl", listOf("flyctl" to "flyctl"), downloadMb = 16, installedMb = 77, login = FlyLogin),

    // The Java page's parts (decision 101): one JDK, or Kotlin, at a time; no tile or Updates row of their own. apt's sizes.
    Java17("java-17", "Java 17", listOf(OPENJDK_17 to "Java 17"), downloadMb = 104, installedMb = 227),
    Java21("java-21", "Java 21", listOf("openjdk-21" to "Java 21"), downloadMb = 113, installedMb = 258),
    Java25("java-25", "Java 25", listOf("openjdk-25" to "Java 25"), downloadMb = 123, installedMb = 267),
    Kotlin("kotlin", "Kotlin", listOf("kotlin" to "Kotlin"), downloadMb = 84, installedMb = 93),
    ;

    val main: String get() = packages.first().first
    val removeOption: String get() = "$option-remove"
    val installSteps: List<String> get() = listOf("Checking the device", "Installing $title")
    val removeSteps: List<String> get() = listOf("Checking the device", "Removing $title")
    val repairSteps: List<String> get() = repair?.let { listOf("Checking the device", it.step) } ?: installSteps

    /** False for Java 17 alone: the script never removes it (`PKG_TOOL_NEVER`), so the page offers no Remove. */
    val canRemove: Boolean get() = packages.any { it.first !in NEVER_REMOVED }

    /** [packages] and [extras]: what it is on the phone. */
    private val shown: List<Pair<String, String>> get() = packages + extras

    /**
     * What its Updates row follows, with names: its main package; for Java every JDK and Kotlin the tile covers (21, 25
     * and Kotlin, whichever are installed), never Java 17, the Android tool's ([NEVER_REMOVED]).
     */
    val updated: List<Pair<String, String>> get() = if (this == Java) shown.filter { it.first !in NEVER_REMOVED } else packages.take(1)

    /** Every package worth reading from dpkg for this tool: its own, the one that holds part of it, and [alsoWatched]. */
    val watched: Set<String> get() = shown.map { it.first }.toSet() + listOfNotNull(heldBy?.pkg) + alsoWatched

    /** On the phone: its main package (or one of [extras]) is installed, whoever put it there. */
    fun installed(pkgs: Map<String, DpkgStatus.Package>): Boolean = main in pkgs || extras.any { it.first in pkgs }

    /**
     * What keeps it from working the way its tile installs it, read from the phone, or null: Ruby without its gems
     * setting (after a `pkg install ruby` in a terminal), `java` leading elsewhere while 17 is there, or Gradle's JDK
     * list behind the JDKs on the phone (a `pkg install openjdk-25` in a terminal). Repair puts it right.
     */
    fun problem(pkgs: Map<String, DpkgStatus.Package>, disk: PackageDisk): String? = when {
        !installed(pkgs) -> null
        shell != null && shell.file !in disk.profiles -> "${shell.label} missing"
        this == Java && JAVA_17 in pkgs && javaDefault(disk).let { it != null && it != "17" } -> "java isn't Java 17"
        this == Java && !disk.gradleJdks.current -> "Gradle JDK list out of date"
        else -> null
    }

    /** Its other packages that aren't there (Python without uv): their names, for "Add uv". Java's page has a row each. */
    fun missing(pkgs: Map<String, DpkgStatus.Package>): List<String> =
        if (!installed(pkgs) || this == Java) emptyList() else packages.filter { it.first !in pkgs }.map { it.second }

    /** Versions as the page lists them ("Python" to "3.14.8"), only for what is installed. */
    fun versions(pkgs: Map<String, DpkgStatus.Package>): List<Pair<String, String>> =
        shown.mapNotNull { (pkg, label) -> pkgs[pkg]?.let { label to DpkgStatus.display(it.version) } }

    /** Its installed packages' size in MB, from dpkg; null when none is there or dpkg gives no sizes. */
    fun sizeMb(pkgs: Map<String, DpkgStatus.Package>): Int? = DpkgStatus.sizeMb(shown.mapNotNull { pkgs[it.first] })

    /** What keeps part of it now (Headless Chrome is installed), or null. */
    fun held(pkgs: Map<String, DpkgStatus.Package>): HeldBy? = heldBy?.takeIf { it.pkg in pkgs }

    /** The names of its installed parts Uninstall would really take away; empty when everything stays. */
    fun removable(pkgs: Map<String, DpkgStatus.Package>): List<String> {
        val kept = held(pkgs)?.keeps.orEmpty()
        return shown.filter { it.first in pkgs && it.first !in kept }.map { it.second }
    }

    /** The names of the parts that stay because of [held]. */
    fun kept(pkgs: Map<String, DpkgStatus.Package>): List<String> {
        val kept = held(pkgs)?.keeps.orEmpty()
        return shown.filter { it.first in pkgs && it.first in kept }.map { it.second }
    }

    /**
     * The uninstall sheet's text: what goes, what stays for another tool, then [note]. "Headless Chrome needs Python
     * and pip, so they stay. uv is removed."
     */
    fun removeLine(pkgs: Map<String, DpkgStatus.Package>, note: String): String {
        val gone = removable(pkgs)
        val kept = kept(pkgs)
        val stays = held(pkgs)?.takeIf { kept.isNotEmpty() }?.let { "${it.name} needs ${names(kept)}, so ${if (kept.size == 1) "it stays" else "they stay"}." }
        val goes = gone.takeIf { it.isNotEmpty() }?.let { "${names(it)} ${if (it.size == 1) "is" else "are"} removed." }
        return listOfNotNull(stays, goes, note).joinToString(" ")
    }

    companion object {
        /** The package the Android tool's builds use; no package tool ever removes it (`PKG_TOOL_NEVER`). */
        const val JAVA_17 = OPENJDK_17

        /** What no package tool removes, the setup script's `PKG_TOOL_NEVER`: Java 17, which Android builds use. */
        val NEVER_REMOVED: Set<String> = setOf(OPENJDK_17, "$OPENJDK_17-x")

        /** The Java page's rows, oldest JDK first (decision 101). */
        val JAVA_PARTS: List<PackageTool> get() = listOf(Java17, Java21, Java25, Kotlin)

        /** The JDK `java` leads to now ("17"), from where `$PREFIX/bin/java` resolves; null when it leads to none. */
        fun javaDefault(disk: PackageDisk): String? = disk.javaDefault?.let { Regex("""/java-(\d+)-openjdk/""").find(it)?.groupValues?.get(1) }

        /** Every package any tool watches, for one read of dpkg's status. */
        val allWatched: Set<String> get() = entries.flatMap { it.watched }.toSet()

        /** "Python and pip", "uv", "a, b and c". */
        fun names(items: List<String>): String = when (items.size) {
            0 -> ""
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        }
    }
}
