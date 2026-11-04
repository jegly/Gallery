package com.gal.ui.screens.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gal.editor.EditorScreen
import com.gal.editor.EditorViewModel
import com.gal.ui.screens.settings.SettingsViewModel
import com.gal.ui.theme.GalTheme
import dagger.hilt.android.AndroidEntryPoint

/** Hosts the photo editor ([EditorScreen], powered by Grainhouse) for one image. */
@AndroidEntryPoint
class EditActivity : ComponentActivity() {

    companion object {
        fun launch(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, EditActivity::class.java).putExtra(EditorViewModel.EXTRA_URI, uri.toString())
            )
        }
    }

    private val editor: EditorViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()

    private val writeLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) editor.saveInPlace() else editor.reportWriteDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        if (intent.getStringExtra(EditorViewModel.EXTRA_URI) == null) {
            finish()
            return
        }

        setContent {
            val s by settings.state.collectAsStateWithLifecycle()
            val view = LocalView.current
            DisposableEffect(s.filterTouches) {
                view.filterTouchesWhenObscured = s.filterTouches
                onDispose { }
            }
            LaunchedEffect(editor.done) {
                if (editor.done) finish()
            }
            GalTheme(
                themeMode = s.themeMode,
                catppuccinAccent = s.catppuccinAccent,
                draculaAccent = s.draculaAccent,
                ptyxisPalette = s.ptyxisPalette,
                appFont = s.appFont,
            ) {
                EditorScreen(editor, onBack = ::finish, onSaveInPlace = ::requestWriteAccess)
            }
        }
    }

    /** Asks the system for permission to change the original (the "Allow Gallery to modify…" prompt). */
    private fun requestWriteAccess() {
        try {
            val pi = MediaStore.createWriteRequest(contentResolver, listOf(editor.sourceUri))
            writeLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            editor.reportWriteDenied(e.message)
        }
    }
}
