package com.tegami.domain

/** Every failure the app shows, with a message written for people. Never carries secrets or amounts. */
sealed class TegamiError(message: String) : Exception(message) {
    data class Network(val detail: String) : TegamiError("Network error: $detail")
    data object RateLimited : TegamiError("The public RPC is rate-limiting requests") { private fun readResolve(): Any = RateLimited }
    data class Rpc(val code: Int, val detail: String) : TegamiError("RPC error $code: $detail")
    data class Parse(val detail: String) : TegamiError("Unexpected response: $detail")
    data object WalletNotFound : TegamiError("No Solana wallet app found") { private fun readResolve(): Any = WalletNotFound }
    data class WalletRejected(val detail: String) : TegamiError("Wallet declined: $detail")
    data object NotConnected : TegamiError("No wallet connected") { private fun readResolve(): Any = NotConnected }
    data object WatchOnly : TegamiError("This address is watch-only") { private fun readResolve(): Any = WatchOnly }
    data class Invalid(val detail: String) : TegamiError(detail)

    /** Short, actionable copy for the UI. */
    val userMessage: String
        get() = when (this) {
            is Network -> "Can't reach the Solana network. Check your connection and try again."
            RateLimited -> "The public devnet RPC is busy. Wait a few seconds and try again."
            is Rpc -> "The Solana RPC returned an error ($code). Try again shortly."
            is Parse -> "The network sent something unexpected. Try again."
            WalletNotFound -> "Install a Solana wallet that supports Mobile Wallet Adapter (for example Solflare or Phantom)."
            is WalletRejected -> "The wallet didn't approve the request."
            NotConnected -> "Connect a wallet first."
            WatchOnly -> "You're watching this address without its wallet. Connect the wallet to sign."
            is Invalid -> detail
        }

    companion object {
        fun from(t: Throwable): TegamiError = t as? TegamiError ?: Network(t.message ?: t::class.simpleName.orEmpty())
    }
}
