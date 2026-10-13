package com.tegami.data

import com.tegami.config.NetworkConfig
import com.tegami.domain.Letter
import com.tegami.domain.LetterRules
import com.tegami.domain.TegamiError
import com.tegami.solana.MintCapabilities
import com.tegami.solana.SignatureInfo
import com.tegami.solana.SolanaRpc
import com.tegami.solana.TransferParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class LettersState(
    val owner: String? = null,
    val letters: List<Letter> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: TegamiError? = null,
    val canLoadMore: Boolean = false,
    val updatedAt: Instant? = null,
)

/**
 * Incoming letters for one owner, read live from the cluster: the owner's token accounts for the configured
 * mint, their signatures (newest first, paged), then each transaction. Kept in memory only.
 */
@Singleton
class LetterRepository @Inject constructor(
    private val rpc: SolanaRpc,
    private val config: NetworkConfig,
) {
    private val _state = MutableStateFlow(LettersState())
    val state: StateFlow<LettersState> = _state
    private val mutex = Mutex()

    /** Per token account: the oldest signature fetched so far, or null once that account is exhausted. */
    private var cursors: Map<String, String?> = emptyMap()
    private var mint: MintCapabilities? = null

    fun letter(signature: String): Letter? = _state.value.letters.firstOrNull { it.signature == signature }

    suspend fun refresh(owner: String) = mutex.withLock {
        if (_state.value.owner != owner) _state.value = LettersState(owner = owner)
        _state.update { it.copy(loading = true, error = null) }
        try {
            val accounts = rpc.tokenAccounts(owner, config.asset.mint)
            val pages = accounts.associateWith { rpc.signatures(it, before = null, limit = PAGE) }
            val letters = load(owner, pages.values.flatten())
            cursors = pages.mapValues { (_, sigs) -> sigs.lastOrNull()?.signature?.takeIf { sigs.size == PAGE } }
            _state.update {
                it.copy(letters = letters, loading = false, canLoadMore = cursors.values.any { c -> c != null }, updatedAt = Instant.now())
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(loading = false, error = TegamiError.from(e)) }
        }
    }

    suspend fun loadMore() = mutex.withLock {
        val owner = _state.value.owner ?: return@withLock
        val open = cursors.filterValues { it != null }
        if (open.isEmpty()) return@withLock
        _state.update { it.copy(loadingMore = true, error = null) }
        try {
            val pages = open.mapValues { (account, before) -> rpc.signatures(account, before, PAGE) }
            val older = load(owner, pages.values.flatten())
            cursors = cursors + pages.mapValues { (_, sigs) -> sigs.lastOrNull()?.signature?.takeIf { sigs.size == PAGE } }
            _state.update {
                val merged = (it.letters + older).distinctBy { l -> l.signature }.sortedByDescending { l -> l.slot }
                it.copy(letters = LetterRules.markDuplicateInvoices(merged), loadingMore = false,
                    canLoadMore = cursors.values.any { c -> c != null })
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(loadingMore = false, error = TegamiError.from(e)) }
        }
    }

    fun clear() {
        cursors = emptyMap()
        _state.value = LettersState()
    }

    /** Cached for the session: the mint's extensions don't change often enough to re-read on every screen. */
    suspend fun mintCapabilities(): MintCapabilities? = mint ?: rpc.mintCapabilities(config.asset.mint).also { mint = it }

    private suspend fun load(owner: String, sigs: List<SignatureInfo>): List<Letter> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        sigs.distinctBy { it.signature }.map { info ->
            async {
                gate.withPermit {
                    val tx = rpc.transaction(info.signature) ?: return@withPermit null
                    val transfer = TransferParser.parse(info.signature, tx, owner, config.asset.mint) ?: return@withPermit null
                    LetterRules.toLetter(transfer, LetterRules.settlement(info.failed, info.confirmationStatus), config)
                }
            }
        }.awaitAll().filterNotNull().sortedByDescending { it.slot }.let(LetterRules::markDuplicateInvoices)
    }

    private companion object {
        const val PAGE = 25
        const val PARALLEL = 4 // the public devnet RPC rate-limits bursts
    }
}
