package com.gal.ui.screens.viewer

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gal.BuildConfig
import com.gal.security.SignatureVerifier
import com.gal.ui.screens.settings.SettingsViewModel
import com.gal.ui.theme.GalTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Shows one photo or video handed to Gal by another app (ACTION_VIEW), e.g. a camera's
 * "open last photo" button.
 *
 * This is a separate activity on purpose. ACTION_VIEW used to land on MainActivity, which is
 * singleTask: Android then brought Gal's own task to the front and stacked the viewer on top
 * of the timeline, so Back went to Gal's timeline instead of the app that opened the photo, and
 * Gal's own navigation state was disturbed. This activity uses the standard launch mode and an
 * empty task affinity, so it opens in (or next to) the caller's task, never inside Gal's main
 * task, and Back simply finishes it and returns to the caller.
 */
@AndroidEntryPoint
class ExternalViewerActivity : FragmentActivity() {

    @Inject lateinit var signatureVerifier: SignatureVerifier
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!BuildConfig.DEBUG && !signatureVerifier.isSignedByTrustedCert(this)) {
            finish()
            return
        }

        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        if (uri == null) {
            finish()
            return
        }

        enableEdgeToEdge()

        setContent {
            val settings by settingsViewModel.state.collectAsStateWithLifecycle()
            val view = LocalView.current

            // Tapjacking protection, same as the main app.
            DisposableEffect(settings.filterTouches) {
                view.filterTouchesWhenObscured = settings.filterTouches
                onDispose { view.filterTouchesWhenObscured = false }
            }

            GalTheme(
                themeMode        = settings.themeMode,
                catppuccinAccent = settings.catppuccinAccent,
                draculaAccent    = settings.draculaAccent,
                ptyxisPalette    = settings.ptyxisPalette,
                appFont          = settings.appFont,
            ) {
                IntentViewerScreen(uri = uri, onBack = { finish() })
            }
        }
    }
}
