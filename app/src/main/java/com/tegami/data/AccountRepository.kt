package com.tegami.data

import android.content.SharedPreferences
import android.net.Uri
import com.funkatronics.encoders.Base58
import com.tegami.domain.TegamiError
import com.tegami.proof.Ed25519
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.AdapterOperations
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The address whose letters Tegami shows. Only a connected wallet can sign; Tegami itself holds no keys. */
data class Account(val address: String, val label: String, val kind: Kind) {
    enum class Kind { Wallet, WatchOnly }
    val canSign: Boolean get() = kind == Kind.Wallet
}

/** How Tegami introduces itself to the wallet app. */
object WalletIdentity {
    val uri: Uri = Uri.parse("https://tegami.local")
    val icon: Uri = Uri.parse("favicon.ico")
    const val NAME = "Tegami"
}

/**
 * Mobile Wallet Adapter, adapted from the starter app: the wallet holds the keys; Tegami stores only the
 * public address, its label and the MWA auth token (app-private prefs; backups disabled in the manifest).
 */
@Singleton
class AccountRepository @Inject constructor(
    private val adapter: MobileWalletAdapter,
    private val prefs: SharedPreferences,
) {
    private val _account = MutableStateFlow(load())
    val account: StateFlow<Account?> = _account

    private fun load(): Account? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val kind = runCatching { Account.Kind.valueOf(prefs.getString(KEY_KIND, "") ?: "") }.getOrNull() ?: return null
        if (kind == Account.Kind.Wallet) adapter.authToken = prefs.getString(KEY_TOKEN, null) ?: return null
        return Account(address, prefs.getString(KEY_LABEL, "") ?: "", kind)
    }

    suspend fun connect(sender: ActivityResultSender): Account =
        when (val result = adapter.connect(sender)) {
            is TransactionResult.Success -> {
                val auth = result.authResult
                Account(Base58.encodeToString(auth.publicKey), auth.accountLabel ?: "Wallet", Account.Kind.Wallet).also {
                    prefs.edit().putString(KEY_ADDRESS, it.address).putString(KEY_LABEL, it.label)
                        .putString(KEY_KIND, it.kind.name).putString(KEY_TOKEN, auth.authToken).apply()
                    _account.value = it
                }
            }
            is TransactionResult.NoWalletFound -> throw TegamiError.WalletNotFound
            is TransactionResult.Failure -> throw TegamiError.WalletRejected(result.e.message ?: "")
        }

    /** Read-only: shows an address's letters without any ability to sign for it. */
    fun watch(address: String, label: String = "Watch-only"): Account {
        val clean = address.trim()
        if (!isAddress(clean)) throw TegamiError.Invalid("That isn't a Solana address.")
        adapter.authToken = null
        return Account(clean, label, Account.Kind.WatchOnly).also {
            prefs.edit().putString(KEY_ADDRESS, it.address).putString(KEY_LABEL, it.label)
                .putString(KEY_KIND, it.kind.name).remove(KEY_TOKEN).apply()
            _account.value = it
        }
    }

    /**
     * Asks the wallet to sign [message] (shown to the user as text in the wallet). The returned signature is
     * checked locally before use, so a misbehaving wallet can't hand back a signature that won't verify.
     */
    suspend fun signMessage(sender: ActivityResultSender, message: String): String {
        val current = _account.value ?: throw TegamiError.NotConnected
        if (!current.canSign) throw TegamiError.WatchOnly
        val address = Base58.decode(current.address)
        val result = walletTransact(sender) { signMessagesDetached(arrayOf(message.toByteArray()), arrayOf(address)) }
        val sig = when (result) {
            is TransactionResult.Success -> result.payload.messages.firstOrNull()?.signatures?.firstOrNull()
                ?: throw TegamiError.WalletRejected("no signature returned")
            is TransactionResult.NoWalletFound -> throw TegamiError.WalletNotFound
            is TransactionResult.Failure -> throw TegamiError.WalletRejected(result.e.message ?: "")
        }
        if (!Ed25519.verify(address, message.toByteArray(), sig)) throw TegamiError.WalletRejected("signature did not verify")
        return Base58.encodeToString(sig)
    }

    /**
     * Wallets rotate the auth token when they approve a request, so the newest is saved each time; a saved
     * token the wallet no longer accepts is dropped and the call retried once with fresh authorization.
     */
    private suspend fun <T> walletTransact(
        sender: ActivityResultSender,
        block: suspend AdapterOperations.(MobileWalletAdapterClient.AuthorizationResult) -> T,
    ): TransactionResult<T> {
        var result = adapter.transact(sender, block = block)
        if (result is TransactionResult.Failure && adapter.authToken != null &&
            "authoriz" in (result.e.message ?: "").lowercase()) {
            adapter.authToken = null
            result = adapter.transact(sender, block = block)
        }
        if (result is TransactionResult.Success) prefs.edit().putString(KEY_TOKEN, result.authResult.authToken).apply()
        return result
    }

    fun disconnect() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_LABEL).remove(KEY_KIND).remove(KEY_TOKEN).apply()
        adapter.authToken = null
        _account.value = null
    }

    companion object {
        private const val KEY_ADDRESS = "account_address"
        private const val KEY_LABEL = "account_label"
        private const val KEY_KIND = "account_kind"
        private const val KEY_TOKEN = "wallet_auth_token"

        fun isAddress(text: String): Boolean =
            text.length in 32..44 && runCatching { Base58.decode(text).size == 32 }.getOrDefault(false)
    }
}
