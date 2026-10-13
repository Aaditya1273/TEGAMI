package com.tegami.domain

import com.tegami.config.NetworkConfig
import com.tegami.solana.IncomingTransfer
import java.time.Instant

/**
 * One incoming payment as Tegami presents it. Each property is a separate claim with its own evidence;
 * none implies another. In particular, nothing here says where the payer's money came from.
 */
data class Letter(
    val signature: String,
    val slot: Long,
    val time: Instant?,
    val settlement: Settlement,
    val payer: Payer,
    /** Null when the amount is encrypted on-chain and this build cannot decrypt it. */
    val amount: Money?,
    val privacy: AmountPrivacy,
    val seal: Seal,
    /** The payer's note, sanitized. Untrusted text: displayed, never acted on. */
    val note: String?,
    /** The same payer used this invoice reference on another payment. */
    val duplicateInvoice: Boolean = false,
) {
    val noteLooksLikeInstruction: Boolean get() = note?.let(UntrustedText::looksLikeInstruction) == true
}

/** Network status, straight from the cluster. "Finalized" is the only state treated as settled. */
enum class Settlement { Confirmed, Finalized, Failed }

/** Who approved the transfer, and what the build's registry says about that address. */
sealed interface Payer {
    val address: String

    /** Listed in the bundled registry. Says nothing about the origin of the payer's funds. */
    data class Registered(override val address: String, val name: String, val registry: String) : Payer
    data class Unregistered(override val address: String) : Payer
}

enum class AmountPrivacy {
    /** An ordinary transfer: the amount is public on-chain. */
    Public,
    /** A Token-2022 confidential transfer: the amount is encrypted; addresses, mint and time are public. */
    Confidential,
}

sealed interface Seal {
    /** Registered payer + payer signed this exact transfer + a well-formed `tegami:v1` memo in the same transaction. */
    data class Sealed(val invoice: String) : Seal
    /** A seal memo is present but cannot be trusted. */
    data class Untrusted(val invoice: String, val reason: UntrustedReason) : Seal
    data object Malformed : Seal
    data object None : Seal
}

enum class UntrustedReason { PayerNotRegistered, PayerDidNotSign, TransactionFailed }

object LetterRules {
    fun settlement(failed: Boolean, confirmationStatus: String?): Settlement = when {
        failed -> Settlement.Failed
        confirmationStatus == "finalized" -> Settlement.Finalized
        else -> Settlement.Confirmed
    }

    fun toLetter(t: IncomingTransfer, settlement: Settlement, config: NetworkConfig): Letter {
        val payer = config.registeredPayer(t.payer)
            ?.let { Payer.Registered(t.payer, it.name, config.registry.name) }
            ?: Payer.Unregistered(t.payer)
        val seal = when (val memo = SealMemo.parse(t.memo)) {
            SealMemo.None -> Seal.None
            SealMemo.Malformed -> Seal.Malformed
            is SealMemo.Valid -> when {
                settlement == Settlement.Failed -> Seal.Untrusted(memo.invoice, UntrustedReason.TransactionFailed)
                payer !is Payer.Registered -> Seal.Untrusted(memo.invoice, UntrustedReason.PayerNotRegistered)
                !t.payerSigned -> Seal.Untrusted(memo.invoice, UntrustedReason.PayerDidNotSign)
                else -> Seal.Sealed(memo.invoice)
            }
        }
        val note = t.memo?.takeIf { SealMemo.parse(it) == SealMemo.None }?.let(UntrustedText::sanitize)?.ifEmpty { null }
        return Letter(
            signature = t.signature,
            slot = t.slot,
            time = t.blockTime?.let(Instant::ofEpochSecond),
            settlement = settlement,
            payer = payer,
            amount = t.amountUnits?.let { Money(it, config.asset.decimals, config.asset.symbol) },
            privacy = if (t.confidential) AmountPrivacy.Confidential else AmountPrivacy.Public,
            seal = seal,
            note = note,
        )
    }

    /** Flags every sealed letter whose (payer, invoice) pair appears more than once. */
    fun markDuplicateInvoices(letters: List<Letter>): List<Letter> {
        val counts = letters.mapNotNull { l -> (l.seal as? Seal.Sealed)?.let { l.payer.address to it.invoice } }
            .groupingBy { it }.eachCount()
        return letters.map { l ->
            val key = (l.seal as? Seal.Sealed)?.let { l.payer.address to it.invoice }
            if (key != null && (counts[key] ?: 0) > 1) l.copy(duplicateInvoice = true) else l
        }
    }

    /** Letters the user should look at: unregistered payers, untrusted seals, encrypted amounts, duplicates, failures. */
    fun needsAttention(l: Letter): Boolean =
        l.payer is Payer.Unregistered || l.seal is Seal.Untrusted || l.seal == Seal.Malformed ||
            l.duplicateInvoice || l.settlement == Settlement.Failed || l.noteLooksLikeInstruction
}
