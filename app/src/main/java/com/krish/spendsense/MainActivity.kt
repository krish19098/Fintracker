package com.krish.spendsense

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.krish.spendsense.ingest.Work
import com.krish.spendsense.ui.AppRoot
import com.krish.spendsense.ui.LaunchRequest
import kotlinx.coroutines.launch

/** Single activity. FragmentActivity is required by BiometricPrompt. */
class MainActivity : FragmentActivity() {
    private val locked = mutableStateOf(false)
    private val launch = mutableStateOf<LaunchRequest?>(null)
    private var stoppedAt = 0L
    private var firstStart = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        // Only a fresh start reads the launch intent; after rotation or process death the restored
        // back stack already contains the screen, and re-reading it would open it a second time.
        if (savedInstanceState == null) launch.value = LaunchRequest.from(intent)
        val container = (application as SpendSenseApp).container
        setContent {
            // AppRoot applies SpendSenseTheme itself: its colours follow today's spending.
            AppRoot(container, locked.value, launch.value, onUnlock = ::authenticate, onLaunchHandled = { launch.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launch.value = LaunchRequest.from(intent)
    }

    override fun onStart() {
        super.onStart()
        Work.scanNow(this)
        val away = SystemClock.elapsedRealtime() - stoppedAt
        val container = (application as SpendSenseApp).container
        lifecycleScope.launch {
            val s = container.settings.get()
            if (s.appLock && s.onboardingDone && canAuthenticate() && (firstStart || away > 30_000)) {
                locked.value = true
                authenticate()
            }
            firstStart = false
        }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    private fun canAuthenticate() =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate() {
        if (!canAuthenticate()) {
            locked.value = false
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                locked.value = false
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock SpendSense")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }
}
