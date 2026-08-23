package com.gal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gal.BuildConfig
import com.gal.data.local.MediaStoreRefresher
import com.gal.security.SignatureVerifier
import javax.inject.Inject
import com.gal.ui.navigation.GalNavHost
import com.gal.ui.screens.settings.SettingsViewModel
import com.gal.ui.theme.GalTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var signatureVerifier: SignatureVerifier
    @Inject lateinit var refresher: MediaStoreRefresher
    private val settingsViewModel: SettingsViewModel by viewModels()

    // Holds the URI from an external ACTION_VIEW intent so the composition can react to it.
    // Reset to null after the navigation is consumed so the same URI can be re-opened later.
    private val viewIntentUri = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (!BuildConfig.DEBUG && !signatureVerifier.isSignedByTrustedCert(this)) {
            showTamperDialog()
            return
        }

        enableEdgeToEdge()

        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            viewIntentUri.value = intent.data
        }

        setContent {
            val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
            val intentUri by viewIntentUri.collectAsStateWithLifecycle()
            val view = LocalView.current

            // App-wide screenshot blocking (FLAG_SECURE on the main window)
            DisposableEffect(settingsState.blockScreenshots) {
                val blockScreenshots = settingsState.blockScreenshots
                if (blockScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onDispose {
                    if (blockScreenshots) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            // Tapjacking protection: ignore touches when overlaid by another window
            DisposableEffect(settingsState.filterTouches) {
                view.filterTouchesWhenObscured = settingsState.filterTouches
                onDispose { view.filterTouchesWhenObscured = false }
            }

            GalTheme(
                themeMode        = settingsState.themeMode,
                catppuccinAccent = settingsState.catppuccinAccent,
                draculaAccent    = settingsState.draculaAccent,
                ptyxisPalette    = settingsState.ptyxisPalette,
                appFont          = settingsState.appFont,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var isUnlocked by rememberSaveable { mutableStateOf(false) }
                    var authTriggered by remember { mutableStateOf(false) }

                    // Only handle logic once settings are loaded
                    if (settingsState.isLoaded) {
                        LaunchedEffect(Unit) {
                            if (settingsState.biometricLock && !isUnlocked) {
                                delay(300)
                                authenticate { success ->
                                    if (success) isUnlocked = true
                                }
                            } else if (!settingsState.biometricLock) {
                                isUnlocked = true
                            }
                        }

                        if (isUnlocked || !settingsState.biometricLock) {
                            GalApp(
                                intentUri = intentUri,
                                onIntentConsumed = { viewIntentUri.value = null },
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize().navigationBarsPadding().statusBarsPadding(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Outlined.Lock,
                                        null,
                                        modifier = Modifier.size(72.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(24.dp))
                                    Text(
                                        "Gal is Locked",
                                        style = MaterialTheme.typography.headlineSmall,
                                        modifier = Modifier.padding(bottom = 32.dp)
                                    )
                                    Button(onClick = {
                                        authenticate { success -> if (success) isUnlocked = true }
                                    }) {
                                        Text("Unlock with Biometrics")
                                    }
                                }
                            }
                        }
                    } else {
                        // Loading state
                        Box(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-poll MediaStore + kick the scanner so anything the file manager
        // dropped while we were backgrounded shows up.
        refresher.bump()
    }

    // singleTask activities receive re-delivery here instead of onCreate when already running
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            viewIntentUri.value = intent.data
        }
    }

    private fun showTamperDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Unverified App")
            .setMessage("This copy of Gal has been modified or re-signed. Please install from the official source.")
            .setCancelable(false)
            .setPositiveButton("Exit") { _, _ -> finish() }
            .show()
    }

    private fun authenticate(onResult: (Boolean) -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onResult(false)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onResult(false)
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Gal")
            .setSubtitle("Authenticate to view your gallery")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}

@Composable
private fun GalApp(intentUri: Uri? = null, onIntentConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            requiredPermissions().any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> hasPermission = results.values.any { it } }

    if (hasPermission) {
        GalNavHost(intentUri = intentUri, onIntentConsumed = onIntentConsumed)
    } else {
        PermissionScreen(onRequest = { launcher.launch(requiredPermissions()) })
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.permission_required),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onRequest) {
                Text(stringResource(R.string.grant_permission))
            }
        }
    }
}

private fun requiredPermissions(): Array<String> = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    // Without this, MediaStore redacts GPS EXIF tags from the stream we read for the
    // media viewer's "Info" location row, even though ACCESS_MEDIA_LOCATION is declared
    // in the manifest — it's a runtime-requestable permission like the others above.
    Manifest.permission.ACCESS_MEDIA_LOCATION,
)
