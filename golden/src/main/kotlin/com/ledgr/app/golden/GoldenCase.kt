package com.ledgr.app.golden

import java.io.File
import kotlinx.serialization.json.Json

/**
 * One golden case (docs/12 §4). `run` reads its fixture(s) from [fixturesDir] and returns the
 * canonical JSON text; the runner compares it byte-for-byte with `expected/<expectedFile>`.
 */
interface GoldenCase {
    /** Contract id, e.g. `G-SENDER`. */
    val id: String

    /** File name under `golden/expected/`. */
    val expectedFile: String

    fun run(fixturesDir: File): String
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
