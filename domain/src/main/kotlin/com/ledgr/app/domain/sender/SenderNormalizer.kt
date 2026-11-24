package com.ledgr.app.domain.sender

/**
 * Splits an SMS address into prefix / header / suffix (docs/03 §3).
 *
 * 1. Upper-case and trim.
 * 2. `^\+?\d{10,13}$` -> [SenderType.PHONE_NUMBER] (never a bank).
 * 3. Otherwise split on `-`:
 *    - `PP-HEADER-S`  prefix + header + suffix
 *    - `PP-HEADER`    prefix + header
 *    - `HEADER-S`     header + suffix
 *    - `HEADER`       header only
 *
 * A header is 3..11 letters/digits with at least one letter (11 is the GSM
 * alphanumeric sender-id limit). Anything else is [SenderType.INVALID].
 */
object SenderNormalizer {

    private val phoneNumber = Regex("""^\+?\d{10,13}$""")
    private val prefixPattern = Regex("""^[A-Z]{2}$""")
    private val headerPattern = Regex("""^(?=.*[A-Z])[A-Z0-9]{3,11}$""")

    fun normalize(address: String): Sender {
        val raw = address
        val value = address.trim().uppercase()

        if (phoneNumber.matches(value)) {
            return Sender(raw, SenderType.PHONE_NUMBER, prefix = null, header = null, suffix = SenderSuffix.NONE)
        }

        val parts = value.split('-')
        val parsed = when (parts.size) {
            1 -> build(raw, prefix = null, header = parts[0], suffix = null)
            2 -> {
                val (first, second) = parts
                val asSuffix = suffixOf(second)
                if (asSuffix != null && !prefixPattern.matches(first)) {
                    build(raw, prefix = null, header = first, suffix = asSuffix)
                } else if (prefixPattern.matches(first)) {
                    build(raw, prefix = first, header = second, suffix = null)
                } else {
                    null
                }
            }
            3 -> {
                val (first, second, third) = parts
                val asSuffix = suffixOf(third)
                if (prefixPattern.matches(first) && asSuffix != null) {
                    build(raw, prefix = first, header = second, suffix = asSuffix)
                } else {
                    null
                }
            }
            else -> null
        }
        return parsed ?: invalid(raw)
    }

    private fun build(raw: String, prefix: String?, header: String, suffix: SenderSuffix?): Sender? =
        if (headerPattern.matches(header)) {
            Sender(raw, SenderType.HEADER, prefix, header, suffix ?: SenderSuffix.NONE)
        } else {
            null
        }

    private fun suffixOf(part: String): SenderSuffix? = when (part) {
        "P" -> SenderSuffix.P
        "S" -> SenderSuffix.S
        "T" -> SenderSuffix.T
        "G" -> SenderSuffix.G
        else -> null
    }

    private fun invalid(raw: String) =
        Sender(raw, SenderType.INVALID, prefix = null, header = null, suffix = SenderSuffix.NONE)
}
