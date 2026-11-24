package com.ledgr.app.golden

import com.ledgr.app.domain.rules.RulesBundle
import java.io.File
import kotlin.system.exitProcess

/** Every case in the contract, in the order docs/12 §4 lists them. Later phases append here. */
private val cases: List<GoldenCase> = listOf(
    GSenderCase,
    GSpamCase,
    GParseCase,
    GMerchantCase,
    GDupCase,
    GXferCase,
    GRefundCase,
)

fun main(args: Array<String>) {
    val opts = args.filter { it.startsWith("--") && '=' in it }
        .associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=') }

    val fixtures = File(opts["fixtures"] ?: fail("missing --fixtures=<dir>"))
    val expected = File(opts["expected"] ?: fail("missing --expected=<dir>"))
    val rules = opts["rules"]?.let(::File)
    val private = opts["private"]?.let(::File)

    // `--emit=<dir>` writes what each case produces to <dir> (never to golden/expected). Expected files are only ever
    // changed by hand, after reading the emitted output; the runner never updates them.
    val emit = opts["emit"]?.let(::File)?.also { it.mkdirs() }

    var failures = 0

    // Preflight (not a golden case): the shipped rule files the app loads at start-up must parse and agree.
    if (rules != null) {
        failures += preflightRules(rules)
    }

    for (case in cases) {
        failures += runCase(case, GoldenContext(fixtures, rules), expected, emit)
    }

    if (private != null && private.isDirectory && rules != null) {
        PrivateCoverage.report(private, GoldenRules.load(GoldenContext(fixtures, rules), includeSyntheticTemplates = false))
    }

    if (failures > 0) {
        System.err.println("GOLDEN CHECK FAILED: $failures failure(s)")
        exitProcess(1)
    }
    println("GOLDEN CHECK OK: ${cases.size} case(s)")
}

private fun runCase(case: GoldenCase, context: GoldenContext, expectedDir: File, emit: File?): Int {
    val expectedFile = File(expectedDir, case.expectedFile)
    val actual = try {
        case.run(context)
    } catch (e: Exception) {
        System.err.println("[FAIL] ${case.id}: ${e::class.simpleName}: ${e.message}")
        return 1
    }
    emit?.let { File(it, case.expectedFile).writeText(actual, Charsets.UTF_8) }
    if (!expectedFile.isFile) {
        System.err.println("[FAIL] ${case.id}: missing expected file ${expectedFile.path}")
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

/** Parses all shipped rule files through the same `:domain` code the app uses, including cross-file checks. */
private fun preflightRules(dir: File): Int {
    val names = listOf("banks.yaml", "spam.yaml", "merchants.yaml", "categories.yaml", "ledger.yaml")
    val missing = names.map { File(dir, it) }.filterNot { it.isFile }
    if (missing.isNotEmpty()) {
        missing.forEach { System.err.println("[FAIL] rules preflight: ${it.path} not found") }
        return missing.size
    }
    return try {
        fun text(name: String) = File(dir, name).readText(Charsets.UTF_8)
        val templates = File(dir, "templates").listFiles { f -> f.isFile && f.name.endsWith(".yaml") }.orEmpty()
            .sortedBy { it.name }.associate { "templates/${it.name}" to it.readText(Charsets.UTF_8) }
        val bundle = RulesBundle.parse(
            text("banks.yaml"), text("spam.yaml"), text("merchants.yaml"), text("categories.yaml"),
            ledgerYaml = text("ledger.yaml"), templateYamls = templates,
        )
        println(
            "[ OK ] rules preflight: v${bundle.version}: ${bundle.banks.banks.size} banks " +
                "(${bundle.unverifiedBanks} unverified), ${bundle.merchants.merchants.size} merchants " +
                "(${bundle.unverifiedMerchants} unverified), ${bundle.categories.categories.size} categories, " +
                "${bundle.spam.classes.size} spam classes, ${bundle.templates.sumOf { it.templates.size }} templates " +
                "in ${bundle.templates.size} file(s) (${bundle.unverifiedTemplates} unverified)",
        )
        0
    } catch (e: Exception) {
        System.err.println("[FAIL] rules preflight: ${e.message}")
        1
    }
}

private fun fail(message: String): Nothing {
    System.err.println("golden runner: $message")
    exitProcess(2)
}
