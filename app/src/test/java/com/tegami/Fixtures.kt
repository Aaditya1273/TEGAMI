package com.tegami

import com.tegami.config.NetworkConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Real devnet transactions captured by scripts/devnet_setup.sh (see app/src/test/resources/rpc). */
object Fixtures {
    const val MINT = "4s6BJseWdLKP1izeqxPs2PuQ2ogYcDMybPp6j8Piuioz"
    const val RECIPIENT = "CKLzXqa2a15yYhnnEac4DNz9pwqwWWLynUNGg1ng8Yk3"
    const val STUDIO = "FajbKPcFK4KYyP1tGVUcAvRvkACAz9AMZJAUXzqLTbRy"
    const val AGENCY = "GToXmQEJL254ohq2xF8xaM4gAMmxdossQ1BezG3C6v8P"

    val SEALED = "4Ty76xTFoJafHXFxcct7H2mA5XoyAJYqxMvn6exbJgR2ju8JXLmK9zd1RSHwKmqxGiMneGkmCzvhueXYNfZxUFFx" to "tx_sealed"
    val AGENCY_SEALED = "3fvMvotKuMA2FzmnqHUrir8tkyuah7M4gc3gv7LGBPrHWwgniiFgxzf6ZTXkqZTsrE3oRQboysHKqS7J9PYpmS4Y" to "tx_agency"
    val NO_MEMO = "4KBEAAtRo1FSTPmtRc9dAPvuQNsVABf4fA57r7cHSJ2e1cCCtE9HBF5cSKfnVLtLiiWR5rsMekFF5Up96ZSFe2Tq" to "tx_no_memo"
    val STRANGER = "5eXAxwynXrzEymzS6QVcQYHSsc8NMVi4b1RNjEiWg2DVgx3iuVsn5YrJhfgXvwF6J4QGSjTsDp4bdHdoBtgbsjqr" to "tx_stranger"
    val INJECTION = "42opwasaG21DjxvP3Xh2L898cQM1Uyd9GiDtgNqt1f88zAbN2YS2t7TfwAnxKN9xxoQHJ3sAdjFbR4zqC6o3Sn69" to "tx_injection"
    val CONFIDENTIAL = "px76rkgyVfdrX3Zkv3SBdZBxkR5wmaKb3exRue9yfszjRjjyQZsjhwfNmSTsjqZddffqYBtECVA8rM2QczeW5HT" to "tx_confidential"

    val config = NetworkConfig(
        network = "devnet",
        rpcUrl = "https://api.devnet.solana.com",
        tokenProgram = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",
        asset = NetworkConfig.Asset(MINT, "tUSD", "test", 6),
        registry = NetworkConfig.Registry("Test registry", "test", listOf(
            NetworkConfig.RegisteredPayer(STUDIO, "Kumo Studio"),
            NetworkConfig.RegisteredPayer(AGENCY, "Hoshi Agency"),
        )),
    )

    fun text(name: String): String = Fixtures::class.java.getResource("/rpc/$name.json")!!.readText()

    /** The `result` object, optionally with the recipient address swapped (to sign statements with a test key). */
    fun tx(name: String, recipient: String = RECIPIENT): JsonObject =
        Json.parseToJsonElement(text(name).replace(RECIPIENT, recipient)).jsonObject["result"]!!.jsonObject
}
