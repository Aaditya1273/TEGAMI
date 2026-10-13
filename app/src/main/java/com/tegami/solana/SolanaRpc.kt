package com.tegami.solana

import com.tegami.domain.TegamiError
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class SignatureInfo(val signature: String, val slot: Long, val failed: Boolean, val confirmationStatus: String?)

/** What the mint's Token-2022 extensions say, read from chain. */
data class MintCapabilities(
    val tokenProgram: String,
    val confidentialTransfers: Boolean,
    val autoApproveAccounts: Boolean?,
    /** Whether an auditor ElGamal key is configured: whoever holds it can decrypt every confidential amount. */
    val auditorKeyConfigured: Boolean?,
)

/** Minimal Solana JSON-RPC client: only the read methods Tegami needs. Never signs, never sends. */
class SolanaRpc(private val http: HttpClient, private val url: String) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun tokenAccounts(owner: String, mint: String): List<String> {
        val result = call("getTokenAccountsByOwner", buildJsonArray {
            add(JsonPrimitive(owner))
            add(buildJsonObject { put("mint", mint) })
            add(buildJsonObject { put("encoding", "base64"); put("commitment", "confirmed") })
        })
        return result.jsonObject["value"]!!.jsonArray.map { it.jsonObject["pubkey"]!!.jsonPrimitive.content }
    }

    suspend fun signatures(address: String, before: String?, limit: Int): List<SignatureInfo> {
        val result = call("getSignaturesForAddress", buildJsonArray {
            add(JsonPrimitive(address))
            add(buildJsonObject {
                put("limit", limit)
                put("commitment", "confirmed")
                if (before != null) put("before", before)
            })
        })
        return result.jsonArray.map { e ->
            val o = e.jsonObject
            SignatureInfo(
                signature = o["signature"]!!.jsonPrimitive.content,
                slot = o["slot"]?.jsonPrimitive?.longOrNull ?: 0,
                failed = o["err"].let { it != null && it !is JsonNull },
                confirmationStatus = (o["confirmationStatus"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }

    /** `getTransaction` with jsonParsed encoding; null if the cluster doesn't have it at [commitment]. */
    suspend fun transaction(signature: String, commitment: String = "confirmed"): JsonObject? {
        val result = call("getTransaction", buildJsonArray {
            add(JsonPrimitive(signature))
            add(buildJsonObject {
                put("encoding", "jsonParsed")
                put("commitment", commitment)
                put("maxSupportedTransactionVersion", 0)
            })
        })
        return result as? JsonObject
    }

    suspend fun mintCapabilities(mint: String): MintCapabilities? {
        val result = call("getAccountInfo", buildJsonArray {
            add(JsonPrimitive(mint))
            add(buildJsonObject { put("encoding", "jsonParsed"); put("commitment", "confirmed") })
        })
        val value = (result as? JsonObject)?.get("value") as? JsonObject ?: return null
        val owner = value["owner"]?.jsonPrimitive?.content ?: return null
        val info = value["data"]?.jsonObject?.get("parsed")?.jsonObject?.get("info")?.jsonObject
        val ct = (info?.get("extensions") as? JsonArray)?.map { it.jsonObject }
            ?.firstOrNull { it["extension"]?.jsonPrimitive?.content == "confidentialTransferMint" }
            ?.get("state")?.jsonObject
        return MintCapabilities(
            tokenProgram = owner,
            confidentialTransfers = ct != null,
            autoApproveAccounts = ct?.get("autoApproveNewAccounts")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
            auditorKeyConfigured = ct?.let { it["auditorElgamalPubkey"].let { k -> k != null && k !is JsonNull } },
        )
    }

    private suspend fun call(method: String, params: JsonArray): JsonElement {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params)
        }
        val text = try {
            val response = http.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            if (response.status.value == 429) throw TegamiError.RateLimited
            if (response.status.value !in 200..299) throw TegamiError.Rpc(response.status.value, "HTTP ${response.status.value}")
            response.bodyAsText()
        } catch (e: TegamiError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TegamiError.Network(e.message ?: e::class.simpleName.orEmpty())
        }
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw TegamiError.Parse("RPC returned non-JSON") }
        (obj["error"] as? JsonObject)?.let { err ->
            val code = err["code"]?.jsonPrimitive?.int ?: 0
            if (code == 429) throw TegamiError.RateLimited
            throw TegamiError.Rpc(code, err["message"]?.jsonPrimitive?.contentOrNull ?: "RPC error")
        }
        return obj["result"] ?: JsonNull
    }
}
