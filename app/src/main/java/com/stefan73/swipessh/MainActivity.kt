package com.stefan73.swipessh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.stefan73.swipessh.ui.SshTerminalApp
import com.stefan73.swipessh.ui.home.HomeViewModel
import com.stefan73.swipessh.ui.theme.SshTerminalTheme
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class MainActivity : ComponentActivity() {
    private val homeViewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(applicationContext)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureBouncyCastleProvider()
        ensureNotificationPermission()
        handleLaunchIntent(intent)
        enableEdgeToEdge()

        setContent {
            SshTerminalTheme {
                SshTerminalApp(viewModel = homeViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /** Routes notification actions back into the shared session model before Compose renders. */
    private fun handleLaunchIntent(intent: Intent?) {
        intent ?: return
        homeViewModel.handleLaunchIntent(intent)
    }

    /** Requests notification permission on Android 13+ so foreground-service notifications stay visible. */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Re-registers Bouncy Castle so SSH key algorithms like Ed25519 are available reliably on Android. */
    private fun ensureBouncyCastleProvider() {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}

