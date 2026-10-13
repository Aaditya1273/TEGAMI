package com.tegami.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything network-specific, loaded once from assets/<network>.json. The build picks the network
 * (BuildConfig.NETWORK); nothing switches it at runtime.
 */
@Serializable
data class NetworkConfig(
    val network: String,
    val rpcUrl: String,
    val tokenProgram: String,
    val asset: Asset,
    val registry: Registry,
    /** A devnet address that scripts/devnet_setup.sh pays, for trying Tegami without a wallet. */
    val demoRecipient: String? = null,
) {
    @Serializable
    data class Asset(val mint: String, val symbol: String, val name: String, val decimals: Int)

    /** The payer policy: an address is a "registered payer" only if it is listed here. */
    @Serializable
    data class Registry(val name: String, val basis: String, val payers: List<RegisteredPayer>)

    @Serializable
    data class RegisteredPayer(val address: String, val name: String)

    val isTestNetwork: Boolean get() = network != "mainnet"

    fun registeredPayer(address: String): RegisteredPayer? = registry.payers.firstOrNull { it.address == address }

    fun explorerTx(signature: String) = "https://explorer.solana.com/tx/$signature${cluster()}"
    fun explorerAddress(address: String) = "https://explorer.solana.com/address/$address${cluster()}"
    private fun cluster() = if (network == "mainnet") "" else "?cluster=$network"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): NetworkConfig = json.decodeFromString(serializer(), text).also {
            require(it.rpcUrl.startsWith("https://")) { "RPC must use HTTPS" }
            require(it.asset.decimals in 0..9) { "unsupported decimals" }
        }
    }
}
