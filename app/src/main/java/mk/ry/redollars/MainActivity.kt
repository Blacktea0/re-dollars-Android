package mk.ry.redollars

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.hilt.navigation.compose.hiltViewModel
import javax.inject.Inject
import mk.ry.redollars.bmo.BmoRenderer
import mk.ry.redollars.bmo.LocalBmoRenderer
import mk.ry.redollars.data.MessageRepository
import mk.ry.redollars.push.RedollarsMessagingService
import mk.ry.redollars.ui.chat.ChatScreen
import mk.ry.redollars.ui.chat.Lightbox
import mk.ry.redollars.ui.render.AudioPlayer
import mk.ry.redollars.ui.render.LocalAudioPlayer
import mk.ry.redollars.ui.render.LocalImageViewer
import mk.ry.redollars.ui.theme.RedollarsTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var bmoRenderer: BmoRenderer
    @Inject lateinit var audioPlayer: AudioPlayer
    @Inject lateinit var repo: MessageRepository

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate(): shows the branded splash during cold start,
        // then swaps to postSplashScreenTheme (Theme.Redollars) at the first frame.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        consumeJumpIntent(intent)
        setContent { RedollarsApp(bmoRenderer, audioPlayer) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeJumpIntent(intent)
    }

    private fun consumeJumpIntent(intent: Intent?) {
        val id = intent?.getLongExtra(RedollarsMessagingService.EXTRA_JUMP_MESSAGE_ID, 0L) ?: 0L
        if (id > 0) {
            repo.pushJumpRequests.value = id
            intent?.removeExtra(RedollarsMessagingService.EXTRA_JUMP_MESSAGE_ID)
        }
    }
}

@Composable
private fun RedollarsApp(
    bmoRenderer: BmoRenderer,
    audioPlayer: AudioPlayer,
) {
    RedollarsTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val vm: ChatViewModel = hiltViewModel()
            LaunchedEffect(Unit) { vm.start() }

            var webView by remember { mutableStateOf<WebView?>(null) }
            var lightboxUrl by remember { mutableStateOf<String?>(null) }
            var pendingDownloadUrl by remember { mutableStateOf<String?>(null) }
            val context = LocalContext.current
            // Android 8/9 need WRITE_EXTERNAL_STORAGE before saving to the gallery; 10+
            // uses scoped MediaStore and never asks.
            val downloadLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                pendingDownloadUrl?.let { url ->
                    if (granted) vm.downloadImage(url)
                    else Toast.makeText(context, "需要存储权限才能保存图片", Toast.LENGTH_SHORT).show()
                }
                pendingDownloadUrl = null
            }

            LifecycleStartEffect(Unit) {
                vm.setForeground(true)
                onStopOrDispose { vm.setForeground(false) }
            }

            // The VM requests the rymk-auth flow after login when no valid backend token
            // is stored; drive it in the (now visible) login WebView.
            // Also re-run when webView becomes available so rotation/recreation doesn't drop it.
            LaunchedEffect(vm.oauthRequestUrl, webView) {
                val url = vm.oauthRequestUrl ?: return@LaunchedEffect
                val wv = webView ?: return@LaunchedEffect
                wv.loadUrl(url)
            }
            CompositionLocalProvider(
                LocalImageViewer provides { url -> lightboxUrl = url },
                LocalBmoRenderer provides bmoRenderer,
                LocalAudioPlayer provides audioPlayer,
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (!vm.showLogin) {
                        ChatScreen(
                            vm = vm,
                            onSend = { text ->
                                if (vm.editing != null) vm.submitEdit(text) else vm.sendMessage(text)
                            },
                            onOpenLogin = { vm.showLogin = true },
                        )
                    }

                    // Only interactive login creates Chromium; normal startup and posting use HTTP.
                    if (vm.showLogin) {
                        androidx.compose.runtime.DisposableEffect(Unit) {
                            onDispose { webView?.destroy(); webView = null }
                        }
                        mk.ry.redollars.ui.BangumiWebView(
                            visible = vm.showLogin,
                            onCreated = { webView = it },
                            onResult = vm::onLoggedIn,
                            onCookies = vm::onBangumiCookies,
                            onLog = vm::externalLog,
                            onAuthToken = vm::onAuthToken,
                            onIgnoreList = vm::onIgnoreUsers,
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                    }

                    if (vm.showLogin) {
                        FilledTonalIconButton(
                            onClick = vm::retryAuth,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .statusBarsPadding()
                                .padding(8.dp),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新登录页")
                        }
                        FilledTonalIconButton(
                            onClick = vm::dismissLogin,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(8.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Close login")
                        }
                    }
                }
            }

            lightboxUrl?.let { url ->
                Lightbox(
                    url = url,
                    onDismiss = { lightboxUrl = null },
                    onSaveSticker = { vm.addFavorite(url) },
                    onDownload = {
                        if (Build.VERSION.SDK_INT <= 28 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingDownloadUrl = url
                            downloadLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            vm.downloadImage(url)
                        }
                    },
                )
            }
        }
    }
}
