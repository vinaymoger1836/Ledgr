package com.ledgr.app.domain.sender

/** What kind of address an SMS came from (docs/03 §3). */
enum class SenderType {
    /** Alphanumeric header, e.g. `VM-HDFCBK-S`. The only type a bank can have. */
    HEADER,

    /** Mobile-number sender. Never a bank; never creates a transaction (docs/04). */
    PHONE_NUMBER,

    /** Anything that is neither a phone number nor a recognisable header. */
    INVALID,
}

/** TRAI message-type suffix (docs/01 glossary). */
enum class SenderSuffix {
    /** Promotional. */
    P,

    /** Service. */
    S,

    /** Transactional. */
    T,

    /** Government. */
    G,

    /** No suffix present. */
    NONE,
}

/**
 * A normalised SMS sender.
 *
 * @property raw the address exactly as received
 * @property prefix operator/circle prefix (2 letters), when present
 * @property header the 6-ish character brand header, when [type] is [SenderType.HEADER]
 */
data class Sender(
    val raw: String,
    val type: SenderType,
    val prefix: String?,
    val header: String?,
    val suffix: SenderSuffix,
)
