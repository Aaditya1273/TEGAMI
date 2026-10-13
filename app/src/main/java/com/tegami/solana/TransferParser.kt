package com.tegami.solana

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One incoming token transfer, read from a confirmed transaction. Facts only; no judgement. */
data class IncomingTransfer(
    val signature: String,
    val slot: Long,
    /** Unix seconds, as reported by the cluster. */
    val blockTime: Long?,
    val failed: Boolean,
    val mint: String,
    /** The transfer authority: the account that approved moving the tokens. */
    val payer: String,
    /** True when [payer] is a signer of this transaction (it always should be for a valid transfer). */
    val payerSigned: Boolean,
    val sourceAccount: String,
    val destinationAccount: String,
    /** Base units; null when the amount is encrypted (Token-2022 confidential transfer). */
    val amountUnits: Long?,
    val confidential: Boolean,
    /** Raw memo text from the SPL Memo program, if the transaction carried one. Untrusted. */
    val memo: String?,
)

/**
 * Reads `getTransaction(..., encoding = jsonParsed)` results. Accepts Token and Token-2022 `transfer`,
 * `transferChecked` and Token-2022 `confidentialTransfer`. Pure: no network, no Android.
 */
object TransferParser {
    private val TRANSFER_TYPES = setOf("transfer", "transferChecked", "confidentialTransfer")

    /**
     * The first transfer of [mint] into a token account owned by [recipient], or null if the transaction
     * has none (e.g. the recipient configuring their own account).
     */
    fun parse(signature: String, result: JsonObject, recipient: String, mint: String): IncomingTransfer? {
        val meta = result["meta"].obj() ?: return null
        val message = result["transaction"].obj()?.get("message").obj() ?: return null
        val keys = message["accountKeys"].arr().map { k ->
            val o = k.obj()
            (o?.get("pubkey").str() ?: k.str() ?: "") to (o?.get("signer").bool() ?: false)
        }
        val signers = keys.filter { it.second }.map { it.first }.toSet()

        // Token-account address -> (owner, mint), from the balances the cluster reported for this tx.
        val holders = HashMap<String, Pair<String, String>>()
        for (field in listOf("preTokenBalances", "postTokenBalances")) {
            meta[field].arr().forEach { b ->
                val o = b.obj() ?: return@forEach
                val index = o["accountIndex"].int() ?: return@forEach
                val account = keys.getOrNull(index)?.first ?: return@forEach
                val owner = o["owner"].str() ?: return@forEach
                holders[account] = owner to (o["mint"].str() ?: return@forEach)
            }
        }

        val instructions = message["instructions"].arr() +
            meta["innerInstructions"].arr().flatMap { it.obj()?.get("instructions").arr() }
        val memo = instructions.firstNotNullOfOrNull { ix ->
            val o = ix.obj()
            if (o?.get("program").str() == "spl-memo") o?.get("parsed").str() else null
        }

        for (ix in instructions) {
            val o = ix.obj() ?: continue
            if (o["program"].str() != "spl-token") continue
            val parsed = o["parsed"].obj() ?: continue
            val type = parsed["type"].str()
            if (type !in TRANSFER_TYPES) continue
            val info = parsed["info"].obj() ?: continue
            val destination = info["destination"].str() ?: continue
            val (owner, holderMint) = holders[destination] ?: continue
            val transferMint = info["mint"].str() ?: holderMint
            if (owner != recipient || transferMint != mint) continue
            val authority = info["authority"].str() ?: info["owner"].str() ?: info["multisigAuthority"].str() ?: continue
            val confidential = type == "confidentialTransfer"
            val amount = when {
                confidential -> null
                else -> info["tokenAmount"].obj()?.get("amount").str()?.toLongOrNull() ?: info["amount"].str()?.toLongOrNull()
            }
            if (!confidential && amount == null) continue
            return IncomingTransfer(
                signature = signature,
                slot = result["slot"].long() ?: 0,
                blockTime = result["blockTime"].long(),
                failed = meta["err"].let { it != null && it !is JsonNull },
                mint = transferMint,
                payer = authority,
                payerSigned = authority in signers,
                sourceAccount = info["source"].str() ?: "",
                destinationAccount = destination,
                amountUnits = amount,
                confidential = confidential,
                memo = memo,
            )
        }
        return null
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
}
