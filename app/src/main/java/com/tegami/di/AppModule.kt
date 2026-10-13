package com.tegami.di

import android.content.Context
import android.content.SharedPreferences
import com.tegami.BuildConfig
import com.tegami.config.NetworkConfig
import com.tegami.data.WalletIdentity
import com.tegami.solana.SolanaRpc
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
object AppModule {

    @Provides
    @Singleton
    fun sharedPrefs(@ApplicationContext ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("tegami", Context.MODE_PRIVATE)

    @Provides
    @Singleton
    fun networkConfig(@ApplicationContext ctx: Context): NetworkConfig =
        NetworkConfig.parse(ctx.assets.open("${BuildConfig.NETWORK}.json").bufferedReader().use { it.readText() })
            .also { check(it.network == BuildConfig.NETWORK) { "asset/network mismatch" } }

    @Provides
    @Singleton
    fun walletAdapter(config: NetworkConfig): MobileWalletAdapter =
        MobileWalletAdapter(ConnectionIdentity(WalletIdentity.uri, WalletIdentity.icon, WalletIdentity.NAME)).apply {
            blockchain = when (config.network) {
                "mainnet" -> Solana.Mainnet
                "testnet" -> Solana.Testnet
                else -> Solana.Devnet
            }
        }

    @Provides
    @Singleton
    fun httpClient(): HttpClient = HttpClient(Android) {
        engine {
            connectTimeout = 10_000
            socketTimeout = 20_000
        }
    }

    @Provides
    @Singleton
    fun solanaRpc(http: HttpClient, config: NetworkConfig) = SolanaRpc(http, config.rpcUrl)
}
