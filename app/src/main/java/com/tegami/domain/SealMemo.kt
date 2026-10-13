package com.tegami.domain

/**
 * The seal memo a payer attaches to its own transfer: `tegami:v1;inv=<invoice>`.
 *
 * The memo is ordinary transaction data. It is authenticated only because it sits in a transaction the
 * payer signed; on its own it proves nothing. Anything else in a memo is untrusted text from the sender.
 */
sealed interface SealMemo {
    data class Valid(val invoice: String) : SealMemo
    /** Starts like a Tegami seal but doesn't parse; shown as malformed, never as sealed. */
    data object Malformed : SealMemo
    data object None : SealMemo

    companion object {
        const val PREFIX = "tegami:"
        private val V1 = Regex("""tegami:v1;inv=([A-Za-z0-9._\-]{1,40})""")

        fun parse(memo: String?): SealMemo {
            val text = memo?.trim() ?: return None
            if (!text.startsWith(PREFIX)) return None
            return V1.matchEntire(text)?.let { Valid(it.groupValues[1]) } ?: Malformed
        }
    }
}

/** A payer's free-text note, made safe to display. It is data: nothing in it is ever acted on. */
object UntrustedText {
    const val MAX = 280
    private val INSTRUCTION_HINTS = listOf(
        "ignore", "instruction", "send ", "transfer", "approve", "sign ", "seed", "private key", "password",
        "http://", "https://", "urgent", "verify your",
    )

    /** Strips control and bidi-override characters and caps the length. */
    fun sanitize(raw: String): String = raw
        .filterNot { it.isISOControl() || it in '‪'..'‮' || it in '⁦'..'⁩' }
        .trim()
        .let { if (it.length > MAX) it.take(MAX) + "…" else it }

    /** Heuristic only: used to warn the user, never to decide anything. */
    fun looksLikeInstruction(text: String): Boolean {
        val lower = text.lowercase()
        return INSTRUCTION_HINTS.any { it in lower }
    }
}
