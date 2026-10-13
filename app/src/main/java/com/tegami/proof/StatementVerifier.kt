package com.tegami.proof

import com.funkatronics.encoders.Base58
import com.tegami.config.NetworkConfig
import com.tegami.domain.LetterRules
import com.tegami.domain.Money
import com.tegami.domain.Settlement
import com.tegami.solana.IncomingTransfer
import com.tegami.solana.TransferParser
import kotlinx.serialization.json.JsonObject
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Ed25519 verification via BouncyCastle (reviewed implementation; nothing hand-rolled). */
object Ed25519 {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
        if (publicKey.size != 32 || signature.size != 64) return false
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(message, 0, message.size)
            verifySignature(signature)
        }
    }.getOrDefault(false)

    fun verify(addressB58: String, message: String, signatureB58: String): Boolean = runCatching {
        verify(Base58.decode(addressB58), message.toByteArray(Charsets.UTF_8), Base58.decode(signatureB58))
    }.getOrDefault(false)
}

/** One line of the verification report, in the order the checks ran. */
data class Check(val title: String, val passed: Boolean, val detail: String)

data class DisclosedPayment(val signature: String, val amount: Money, val payerName: String, val time: Instant?)

data class VerificationReport(
    val checks: List<Check>,
    val disclosed: List<DisclosedPayment>,
    val total: Money?,
) {
    val verified: Boolean get() = checks.isNotEmpty() && checks.all { it.passed }
}

/**
 * Re-checks a signed statement against the chain using THIS device's network config and payer registry.
 * [fetchFinalized] returns the `getTransaction` result at `finalized` commitment, or null if not found.
 * Stops at the first failed check, so a report never mixes "passed" with unchecked claims.
 */
class StatementVerifier(
    private val config: NetworkConfig,
    private val fetchFinalized: suspend (signature: String) -> JsonObject?,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun verify(signed: SignedStatement, onCheck: (Check) -> Unit = {}): VerificationReport {
        val s = signed.statement
        val checks = mutableListOf<Check>()
        fun record(c: Check): Boolean { checks += c; onCheck(c); return c.passed }
        fun fail() = VerificationReport(checks, emptyList(), null)

        val formatOk = s.v == IncomeStatement.VERSION && s.network == config.network &&
            s.mint == config.asset.mint && s.decimals == config.asset.decimals && s.thresholdUnits > 0
        if (!record(Check("Format and network", formatOk,
                if (formatOk) "Version ${s.v}, ${s.network}, ${config.asset.symbol}"
                else "Expected ${config.network} / ${config.asset.symbol}; got ${s.network} / ${s.symbol}"))) return fail()

        val sigOk = Ed25519.verify(s.recipient, s.message(), signed.signature)
        if (!record(Check("Wallet signature", sigOk,
                if (sigOk) "Signed by the recipient wallet ${short(s.recipient)}"
                else "The signature does not match the recipient wallet or the statement was altered"))) return fail()

        val t = now().epochSecond
        val windowOk = s.issuedAt <= t + CLOCK_SKEW_S && t < s.expiresAt &&
            s.expiresAt - s.issuedAt <= IncomeStatement.MAX_VALIDITY_DAYS * 86_400
        if (!record(Check("Validity period", windowOk,
                if (windowOk) "Valid until ${Instant.ofEpochSecond(s.expiresAt)}"
                else if (t >= s.expiresAt) "Expired ${Instant.ofEpochSecond(s.expiresAt)}" else "Issue/expiry times are not acceptable"))) return fail()

        val from = runCatching { LocalDate.parse(s.from) }.getOrNull()
        val to = runCatching { LocalDate.parse(s.to) }.getOrNull()
        val listOk = from != null && to != null && !from.isAfter(to) && s.evidence.isNotEmpty() &&
            s.evidence.size <= IncomeStatement.MAX_EVIDENCE && s.evidence.toSet().size == s.evidence.size
        if (!record(Check("Evidence list", listOk,
                if (listOk) "${s.evidence.size} transaction(s), ${s.from} to ${s.to}" else "Empty, duplicated, too long, or bad dates"))) return fail()

        val disclosed = mutableListOf<DisclosedPayment>()
        var total = Money.zero(s.decimals, config.asset.symbol)
        for (sig in s.evidence) {
            val result = fetchFinalized(sig)
            val transfer = result?.let { TransferParser.parse(sig, it, s.recipient, s.mint) }
            val problem = evidenceProblem(transfer, result != null, from!!, to!!)
            if (problem != null) {
                record(Check("Payment ${short(sig)}", false, problem))
                return VerificationReport(checks, disclosed, null)
            }
            val letter = LetterRules.toLetter(transfer!!, Settlement.Finalized, config)
            val amount = letter.amount!!
            val payerName = config.registeredPayer(transfer.payer)!!.name
            disclosed += DisclosedPayment(sig, amount, payerName, letter.time)
            total += amount
            record(Check("Payment ${short(sig)}", true, "${amount.format()} from $payerName, finalized"))
        }

        val enough = total >= s.threshold
        record(Check("Claim", enough,
            if (enough) "Total ${total.format()} ≥ ${s.threshold.format()}" else "Total ${total.format()} < ${s.threshold.format()}"))
        return VerificationReport(checks, disclosed, total)
    }

    private fun evidenceProblem(t: IncomingTransfer?, found: Boolean, from: LocalDate, to: LocalDate): String? = when {
        !found -> "Not found at finalized commitment on ${config.network}"
        t == null -> "Not a ${config.asset.symbol} payment to the statement's wallet"
        t.failed -> "The transaction failed"
        t.confidential || t.amountUnits == null -> "Amount is encrypted; it cannot be counted"
        !t.payerSigned -> "The payer did not sign the transfer"
        config.registeredPayer(t.payer) == null -> "Payer ${short(t.payer)} is not in this device's registry (${config.registry.name})"
        t.blockTime == null -> "The cluster reports no time for this transaction"
        Instant.ofEpochSecond(t.blockTime).atZone(ZoneOffset.UTC).toLocalDate().let { it.isBefore(from) || it.isAfter(to) } ->
            "Outside the stated period"
        else -> null
    }

    private fun short(a: String) = if (a.length > 12) "${a.take(4)}…${a.takeLast(4)}" else a

    companion object {
        const val CLOCK_SKEW_S = 300L
    }
}
