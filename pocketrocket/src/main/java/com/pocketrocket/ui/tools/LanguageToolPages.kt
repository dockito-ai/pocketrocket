package com.pocketrocket.ui.tools

import androidx.compose.runtime.Composable

/*
 * The language tiles (decision 98): Termux packages on the shared package tool page, each with a line or two on what it
 * does and what to know.
 */

/** Settings → Tools → Go: Termux's golang. */
@Composable
fun GoToolPage(onBack: () -> Unit) = PackageToolPage(
    tool = Tool.Go,
    purpose = "Build and run Go programs.",
    onBack = onBack,
    removeNote = "The module and build caches go too. Your projects and the programs in ~/go/bin stay.",
) {
    ToolCard("WHAT IT DOES") {
        ToolBullets(listOf("go build, go run, go test and go mod in your projects.", "Works in terminals and Claude sessions."))
    }
    ToolCard("WHAT IT IS") {
        ToolText("Termux's golang package, updated from Settings › Updates. Pure Go needs nothing else; cgo needs clang (the Android NDK tool has it).")
    }
}

/** Settings → Tools → Deno: Termux's deno. */
@Composable
fun DenoToolPage(onBack: () -> Unit) = PackageToolPage(
    tool = Tool.Deno,
    purpose = "Run JavaScript and TypeScript with Deno.",
    onBack = onBack,
    removeNote = "Its download cache goes too. Your projects stay.",
) {
    ToolCard("WHAT IT DOES") {
        ToolBullets(listOf("deno run, deno test and deno fmt, with npm: and jsr: imports.", "Works in terminals and Claude sessions."))
    }
    ToolCard("WHAT IT IS") {
        ToolText("Termux's deno package, updated from Settings › Updates. Node.js and npm stay as they are.")
    }
}

/** Settings → Tools → Ruby: Termux's ruby, with RubyGems loaded in every shell. */
@Composable
fun RubyToolPage(onBack: () -> Unit) = PackageToolPage(
    tool = Tool.Ruby,
    purpose = "Run Ruby, with gem and bundle.",
    onBack = onBack,
    removeNote = "The gems you installed go too. Your projects stay.",
) {
    ToolCard("WHAT IT DOES") {
        ToolBullets(
            listOf(
                "ruby, gem and bundle in terminals and Claude sessions.",
                "Installed gems load with a plain require: PocketRocket sets RUBYOPT=-rrubygems, which Termux's Ruby needs.",
            ),
        )
    }
    ToolCard("WHAT IT IS") {
        ToolText("Termux's ruby package, updated from Settings › Updates. Gems with native parts build with clang and make (the Android NDK tool has clang).")
    }
}

/** Settings → Tools → PHP: Termux's php. Composer isn't offered (decision 98). */
@Composable
fun PhpToolPage(onBack: () -> Unit) = PackageToolPage(
    tool = Tool.Php,
    purpose = "Run PHP scripts and its built-in server.",
    onBack = onBack,
    removeNote = "Your projects stay.",
) {
    ToolCard("WHAT IT DOES") {
        ToolBullets(listOf("php, and php -S localhost:8000 to serve a folder on the phone.", "Works in terminals and Claude sessions."))
    }
    ToolCard("WHAT IT IS") {
        ToolText("Termux's php package, updated from Settings › Updates. Composer isn't included: Termux's composer package doesn't run on the phone.")
    }
}

/** Settings → Tools → Java and Kotlin: openjdk-21 and kotlin, with Java 17 kept as the default java (decision 98). */
@Composable
fun JavaToolPage(onBack: () -> Unit) = PackageToolPage(
    tool = Tool.Java,
    purpose = "Java 21 and Kotlin, for programs and scripts.",
    onBack = onBack,
    removeNote = "Java 17, if the Android tool has it, stays. Your projects stay.",
) {
    ToolCard("WHAT IT DOES") {
        ToolBullets(listOf("javac, java and jshell from Java 21.", "kotlinc and kotlin for Kotlin programs and .kts scripts."))
    }
    ToolCard("JAVA 17 STAYS THE DEFAULT") {
        ToolText(
            "Android and Flutter builds use Java 17 from the Android tool. While it is on the phone it stays the default java, " +
                "and Java 21 is in \$PREFIX/lib/jvm/java-21-openjdk: run its bin/java, or set JAVA_HOME to it.",
        )
    }
}
