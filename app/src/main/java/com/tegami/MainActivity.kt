package com.tegami

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.tegami.proof.StatementCodec
import com.tegami.ui.IncomingLinks
import com.tegami.ui.LocalWalletSender
import com.tegami.ui.SessionViewModel
import com.tegami.ui.TegamiRoot
import com.tegami.ui.theme.TegamiTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var links: IncomingLinks
    private val session: SessionViewModel by viewModels()
    private val linkCount = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // never held open: data loads inside the app
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sender = ActivityResultSender(this) // must be registered before the activity starts
        if (savedInstanceState == null) handle(intent)

        // Screenshot / recents protection follows the user's setting (on by default).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                session.secureScreen.collect { on ->
                    if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        setContent {
            val reduced by session.reducedMotion.collectAsStateWithLifecycle()
            val links by linkCount
            TegamiTheme(reducedMotion = reduced) {
                CompositionLocalProvider(LocalWalletSender provides sender) {
                    TegamiRoot(session, incomingLinks = links)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Accepts only tegami://verify?s=<token> with a well-formed, size-capped token; anything else is ignored. */
    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "tegami" || uri.host != "verify") return
        val token = uri.getQueryParameter("s") ?: return
        if (StatementCodec.decode(token).isFailure) return
        links.pending.value = token
        linkCount.intValue++
    }
}
