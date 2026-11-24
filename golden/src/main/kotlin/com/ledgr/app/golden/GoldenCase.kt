package com.ledgr.app.golden

import java.io.File
import kotlinx.serialization.json.Json

/** Where a case finds its inputs: its fixtures, and the shipped rule files the app itself loads. */
class GoldenContext(val fixturesDir: File, val rulesDir: File?) {
    fun requireRulesDir(): File = rulesDir ?: error("this case needs --rules=<dir>")
}

/**
 * One golden case (docs/12 §4). `run` reads its fixture(s) via [GoldenContext] and returns the
 * canonical JSON text; the runner compares it byte-for-byte with `expected/<expectedFile>`.
 */
interface GoldenCase {
    /** Contract id, e.g. `G-SENDER`. */
    val id: String

    /** File name under `golden/expected/`. */
    val expectedFile: String

    fun run(context: GoldenContext): String
}

/** One canonical JSON format for every case: 2-space indent, explicit nulls, `\n` line ends, trailing newline. */
object GoldenJson {
    val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = true
        encodeDefaults = true
    }

    fun finish(text: String): String = text.replace("\r\n", "\n").trimEnd('\n') + "\n"
}
