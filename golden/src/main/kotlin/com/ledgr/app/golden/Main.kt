package com.ledgr.app.golden

import com.ledgr.app.domain.rules.RulesBundle
import java.io.File
import kotlin.system.exitProcess

/** Every case in the contract, in the order docs/12 §4 lists them. Later phases append here. */
private val cases: List<GoldenCase> = listOf(
    GSenderCase,
)

fun main(args: Array<String>) {
    val opts = args.filter { it.startsWith("--") && '=' in it }
        .associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=') }

    val fixtures = File(opts["fixtures"] ?: fail("missing --fixtures=<dir>"))
    val expected = File(opts["expected"] ?: fail("missing --expected=<dir>"))
    val rules = opts["rules"]?.let(::File)
    val private = opts["private"]?.let(::File)

    var failures = 0

    // Preflight (not a golden case): the shipped rule files the app loads at start-up must parse and agree.
    if (rules != null) {
        failures += preflightRules(rules)
    }

    for (case in cases) {
        failures += runCase(case, fixtures, expected)
    }

    reportPrivate(private)

    if (failures > 0) {
        System.err.println("GOLDEN CHECK FAILED: $failures failure(s)")
        exitProcess(1)
    }
    println("GOLDEN CHECK OK: ${cases.size} case(s)")
}

private fun runCase(case: GoldenCase, fixtures: File, expectedDir: File): Int {
    val expectedFile = File(expectedDir, case.expectedFile)
    if (!expectedFile.isFile) {
        System.err.println("[FAIL] ${case.id}: missing expected file ${expectedFile.path}")
        return 1
    }
    val actual = try {
        case.run(fixtures)
    } catch (e: Exception) {
        System.err.println("[FAIL] ${case.id}: ${e::class.simpleName}: ${e.message}")
        return 1
    }
    val expectedBytes = expectedFile.readBytes()
    if (expectedBytes.contentEquals(actual.toByteArray(Charsets.UTF_8))) {
        println("[ OK ] ${case.id}")
        return 0
    }
    System.err.println("[FAIL] ${case.id}: output differs from ${expectedFile.name}")
    val want = expectedBytes.toString(Charsets.UTF_8).lines()
    val got = actual.lines()
    val line = (0 until maxOf(want.size, got.size)).firstOrNull { want.getOrNull(it) != got.getOrNull(it) }
    if (line != null) {
        System.err.println("       first difference at line ${line + 1}")
        System.err.println("       expected: ${want.getOrNull(line) ?: "<end of file>"}")
        System.err.println("       actual:   ${got.getOrNull(line) ?: "<end of file>"}")
    }
    return 1
}

/** Parses all four rule files through the same `:domain` code the app uses, including cross-file checks. */
private fun preflightRules(dir: File): Int {
    val names = listOf("banks.yaml", "spam.yaml", "merchants.yaml", "categories.yaml")
    val missing = names.map { File(dir, it) }.filterNot { it.isFile }
    if (missing.isNotEmpty()) {
        missing.forEach { System.err.println("[FAIL] rules preflight: ${it.path} not found") }
        return missing.size
    }
    return try {
        fun text(name: String) = File(dir, name).readText(Charsets.UTF_8)
        val bundle = RulesBundle.parse(text("banks.yaml"), text("spam.yaml"), text("merchants.yaml"), text("categories.yaml"))
        println(
            "[ OK ] rules preflight: v${bundle.version}: ${bundle.banks.banks.size} banks " +
                "(${bundle.unverifiedBanks} unverified), ${bundle.merchants.merchants.size} merchants " +
                "(${bundle.unverifiedMerchants} unverified), ${bundle.categories.categories.size} categories, " +
                "${bundle.spam.classes.size} spam classes",
        )
        0
    } catch (e: Exception) {
        System.err.println("[FAIL] rules preflight: ${e.message}")
        1
    }
}

/** docs/12 §4(c): private data is reported, never asserted. Nothing to run on it in P0. */
private fun reportPrivate(dir: File?) {
    if (dir != null && dir.isDirectory) {
        println("[info] golden/private present: coverage reporting arrives with the parser (P3); nothing asserted.")
    }
}

private fun fail(message: String): Nothing {
    System.err.println("golden runner: $message")
    exitProcess(2)
}
