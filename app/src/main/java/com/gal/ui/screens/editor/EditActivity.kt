package com.gal.ui.screens.editor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.lifecycle.lifecycleScope
import com.canhub.cropper.CropImageView
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class EditActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URI = "extra_uri"
        fun launch(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, EditActivity::class.java).putExtra(EXTRA_URI, uri.toString())
            )
        }
    }

    private enum class Mode { NONE, BRIGHTNESS, CONTRAST, SATURATION, CROP, FILTER, DRAW }

    private lateinit var sourceUri: Uri
    private lateinit var rootFrame: FrameLayout
    private lateinit var cropView: CropImageView
    private lateinit var drawView: DrawOverlayView
    private lateinit var sliderRow: LinearLayout
    private lateinit var sliderLabel: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var cropRow: LinearLayout
    private lateinit var filterRow: HorizontalScrollView
    private lateinit var drawRow: LinearLayout
    private lateinit var undoBtn: Button
    private lateinit var redoBtn: Button

    private var mode = Mode.NONE
    private var originalBitmap: Bitmap? = null
    private var currentBitmap: Bitmap? = null
    private var adjustJob: Job? = null

    /** Mutable edit state; snapshots of this go on the undo stack. */
    private data class EditState(
        val brightness: Float = 0f,
        val contrast: Float = 1f,
        val saturation: Float = 1f,
        val rotation: Float = 0f,
        val flipH: Boolean = false,
        val flipV: Boolean = false,
        val filter: String = "None",
    )

    /**
     * Single history step. [baseSnapshot] is non-null when this step also captures the
     * [originalBitmap] (crop / draw operations that replace it), so undoing past it can
     * restore the pre-operation pixels. Bitmap snapshots are heavy, so most adjustments
     * (sliders / filter / rotate / flip) carry [baseSnapshot] = null and rely on
     * [applyAdjustments] to recompute.
     */
    private data class HistoryEntry(val state: EditState, val baseSnapshot: Bitmap? = null)

    private var state = EditState()
    private val history = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private val historyLimit = 20

    private val writeLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) saveInPlaceAfterPermission()
        else Toast.makeText(this, "Save cancelled", Toast.LENGTH_SHORT).show()
    }

    /**
     * Toolbar Save / Copy handler. If the user is still in CROP or DRAW mode their pending
     * change hasn't been baked into [currentBitmap] yet, so apply it first then save.
     */
    private fun onSaveClicked(asCopy: Boolean) {
        val finish = {
            if (asCopy) saveAsCopy() else confirmAndSaveInPlace()
        }
        when (mode) {
            Mode.CROP -> applyCrop(onDone = finish)
            Mode.DRAW -> { applyDraw(); finish() }
            else -> finish()
        }
    }

    private fun confirmAndSaveInPlace() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Overwrite original?")
            .setMessage("The original file will be replaced with your edits. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> saveInPlaceRequest() }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        val uriString = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        sourceUri = Uri.parse(uriString)

        buildUi()
        loadImage()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // ── Toolbar ──────────────────────────────────────────────────────────
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0A0A0A.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(Button(this).apply {
            text = "←"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { finish() }
        })
        toolbar.addView(TextView(this).apply {
            text = "Edit"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        undoBtn = Button(this).apply {
            text = "↶"
            setTextColor(0xFFBBBBBB.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { undo() }
        }
        redoBtn = Button(this).apply {
            text = "↷"
            setTextColor(0xFFBBBBBB.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { redo() }
        }
        toolbar.addView(undoBtn)
        toolbar.addView(redoBtn)
        toolbar.addView(Button(this).apply {
            text = "Save"
            setTextColor(0xFFCDB8FF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { onSaveClicked(asCopy = false) }
        })
        toolbar.addView(Button(this).apply {
            text = "Copy"
            setTextColor(0xFFCDB8FF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { onSaveClicked(asCopy = true) }
        })

        // ── CropImageView + DrawOverlayView in a FrameLayout ────────────────
        cropView = CropImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            guidelines = CropImageView.Guidelines.ON
        }
        drawView = DrawOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            visibility = View.GONE
        }
        rootFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            addView(cropView)
            addView(drawView)
        }

        // ── Slider row (brightness/contrast/saturation) ──────────────────────
        sliderLabel = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        seekBar = SeekBar(this).apply {
            max = 200
            progress = 100
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    when (mode) {
                        Mode.BRIGHTNESS -> state = state.copy(brightness = (p - 100) / 100f)
                        Mode.CONTRAST   -> state = state.copy(contrast   = (p / 100f).coerceAtLeast(0.01f))
                        Mode.SATURATION -> state = state.copy(saturation = (p / 100f).coerceAtLeast(0f))
                        else -> return
                    }
                    applyAdjustments()
                }
                override fun onStartTrackingTouch(sb: SeekBar) { snapshot() }
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0A0A0A.toInt())
            setPadding(dp(16), dp(4), dp(16), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(sliderLabel)
            addView(seekBar)
        }

        // ── Crop action row (two stacked rows: presets, then Cancel/Apply) ──
        cropRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0A0A.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            visibility = View.GONE
        }
        val cropPresetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val cropPresets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cropPresets.addView(cropPresetBtn("Free") { cropView.setFixedAspectRatio(false) })
        cropPresets.addView(cropPresetBtn("1:1")  { cropView.setFixedAspectRatio(true); cropView.setAspectRatio(1, 1) })
        cropPresets.addView(cropPresetBtn("4:3")  { cropView.setFixedAspectRatio(true); cropView.setAspectRatio(4, 3) })
        cropPresets.addView(cropPresetBtn("16:9") { cropView.setFixedAspectRatio(true); cropView.setAspectRatio(16, 9) })
        cropPresets.addView(cropPresetBtn("Orig") {
            val bm = currentBitmap ?: return@cropPresetBtn
            cropView.setFixedAspectRatio(true); cropView.setAspectRatio(bm.width, bm.height)
        })
        cropPresetScroll.addView(cropPresets)
        cropRow.addView(cropPresetScroll)

        val cropActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        cropActions.addView(Button(this).apply {
            text = "Cancel"
            setTextColor(0xFFAAAAAA.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                mode = Mode.NONE
                cropRow.visibility = View.GONE
                currentBitmap?.let { cropView.setImageBitmap(it) }
            }
        })
        cropActions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        cropActions.addView(Button(this).apply {
            text = "✓ Apply crop"
            setTextColor(0xFF111111.toInt())
            setBackgroundColor(0xFFCDB8FF.toInt())
            setPadding(dp(20), dp(6), dp(20), dp(6))
            setOnClickListener { applyCrop() }
        })
        cropRow.addView(cropActions)

        // ── Filter row (horizontal scroll of preset chips) ───────────────────
        filterRow = HorizontalScrollView(this).apply {
            setBackgroundColor(0xFF0A0A0A.toInt())
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
        }
        val filterChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        FilterPresets.names.forEach { name ->
            filterChips.addView(Button(this).apply {
                text = name
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0x33FFFFFF)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, dp(4), 0) }
                setOnClickListener {
                    snapshot()
                    state = state.copy(filter = name)
                    applyAdjustments()
                }
            })
        }
        filterRow.addView(filterChips)

        // ── Draw row (two stacked: tools, then Clear/Apply) ──────────────────
        drawRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0A0A.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            visibility = View.GONE
        }
        val drawToolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val drawTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(
            0xFFFFFFFF.toInt(), 0xFFFF3B30.toInt(), 0xFFFFCC00.toInt(),
            0xFF34C759.toInt(), 0xFF007AFF.toInt(), 0xFF000000.toInt(),
        ).forEach { c ->
            drawTools.addView(colorSwatch(c) { drawView.color = c })
        }
        drawTools.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })
        listOf(6f, 14f, 28f).forEachIndexed { i, size ->
            drawTools.addView(Button(this).apply {
                text = listOf("S", "M", "L")[i]
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0x33FFFFFF)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, dp(4), 0) }
                setOnClickListener { drawView.strokeWidth = size }
            })
        }
        drawToolsScroll.addView(drawTools)
        drawRow.addView(drawToolsScroll)

        val drawActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        drawActions.addView(Button(this).apply {
            text = "Cancel"
            setTextColor(0xFFAAAAAA.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                drawView.clear()
                mode = Mode.NONE
                drawRow.visibility = View.GONE
                drawView.visibility = View.GONE
            }
        })
        drawActions.addView(Button(this).apply {
            text = "Clear"
            setTextColor(0xFFAAAAAA.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { drawView.clear() }
        })
        drawActions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        drawActions.addView(Button(this).apply {
            text = "✓ Apply drawing"
            setTextColor(0xFF111111.toInt())
            setBackgroundColor(0xFFCDB8FF.toInt())
            setPadding(dp(20), dp(6), dp(20), dp(6))
            setOnClickListener { applyDraw() }
        })
        drawRow.addView(drawActions)

        // ── Tool buttons ─────────────────────────────────────────────────────
        val toolScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(0xFF0A0A0A.toInt())
            isHorizontalScrollBarEnabled = false
        }
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun toolBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 10f
            minimumWidth = dp(64)
            setTextColor(0xFF888888.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { onClick() }
        }
        toolRow.addView(toolBtn("☀\nBright") { enterSliderMode(Mode.BRIGHTNESS) })
        toolRow.addView(toolBtn("◑\nContrast") { enterSliderMode(Mode.CONTRAST) })
        toolRow.addView(toolBtn("◎\nSat") { enterSliderMode(Mode.SATURATION) })
        toolRow.addView(toolBtn("✨\nFilter") { enterFilterMode() })
        toolRow.addView(toolBtn("⊡\nCrop") { enterCropMode() })
        toolRow.addView(toolBtn("↺\n90° L") { snapshot(); state = state.copy(rotation = state.rotation - 90f); applyAdjustments() })
        toolRow.addView(toolBtn("↻\n90° R") { snapshot(); state = state.copy(rotation = state.rotation + 90f); applyAdjustments() })
        toolRow.addView(toolBtn("⇄\nFlip H") { snapshot(); state = state.copy(flipH = !state.flipH); applyAdjustments() })
        toolRow.addView(toolBtn("⇅\nFlip V") { snapshot(); state = state.copy(flipV = !state.flipV); applyAdjustments() })
        toolRow.addView(toolBtn("✎\nDraw") { enterDrawMode() })
        toolScroll.addView(toolRow)

        root.addView(toolbar)
        root.addView(rootFrame)
        root.addView(sliderRow)
        root.addView(cropRow)
        root.addView(filterRow)
        root.addView(drawRow)
        root.addView(toolScroll)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        updateHistoryButtons()
    }

    // ── Mode switches ──────────────────────────────────────────────────────

    private fun hideAllSecondaryRows() {
        sliderRow.visibility = View.GONE
        cropRow.visibility = View.GONE
        filterRow.visibility = View.GONE
        drawRow.visibility = View.GONE
        drawView.visibility = View.GONE
    }

    private fun enterSliderMode(m: Mode) {
        mode = m
        hideAllSecondaryRows()
        sliderLabel.text = when (m) {
            Mode.BRIGHTNESS -> "Brightness"
            Mode.CONTRAST   -> "Contrast"
            Mode.SATURATION -> "Saturation"
            else -> ""
        }
        val p = when (m) {
            Mode.BRIGHTNESS -> ((state.brightness * 100) + 100).toInt()
            Mode.CONTRAST   -> (state.contrast   * 100).toInt()
            Mode.SATURATION -> (state.saturation * 100).toInt()
            else -> 100
        }.coerceIn(0, 200)
        seekBar.progress = p
        sliderRow.visibility = View.VISIBLE
    }

    private fun enterCropMode() {
        mode = Mode.CROP
        hideAllSecondaryRows()
        cropView.setFixedAspectRatio(false)
        cropView.guidelines = CropImageView.Guidelines.ON
        cropRow.visibility = View.VISIBLE
    }

    private fun enterFilterMode() {
        mode = Mode.FILTER
        hideAllSecondaryRows()
        filterRow.visibility = View.VISIBLE
    }

    private fun enterDrawMode() {
        val bm = currentBitmap ?: return
        mode = Mode.DRAW
        hideAllSecondaryRows()
        drawView.attach(bm)
        drawView.visibility = View.VISIBLE
        drawRow.visibility = View.VISIBLE
    }

    // ── Image pipeline ─────────────────────────────────────────────────────

    private fun loadImage() {
        cropView.setImageUriAsync(sourceUri)
        cropView.setOnSetImageUriCompleteListener { _, _, error ->
            if (error != null) {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                lifecycleScope.launch(Dispatchers.Default) {
                    val bm = cropView.getCroppedImage(reqWidth = 0, reqHeight = 0)
                    withContext(Dispatchers.Main) {
                        originalBitmap = bm
                        currentBitmap = bm
                    }
                }
            }
        }
    }

    private fun applyAdjustments() {
        val src = originalBitmap ?: return
        adjustJob?.cancel()
        adjustJob = lifecycleScope.launch(Dispatchers.Default) {
            // 1. Apply rotation + flip via Matrix
            val matrix = Matrix().apply {
                if (state.rotation % 360f != 0f) postRotate(state.rotation)
                if (state.flipH) postScale(-1f, 1f)
                if (state.flipV) postScale(1f, -1f)
            }
            val transformed = if (!matrix.isIdentity)
                Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            else src

            // 2. Build composite ColorMatrix: filter → sat → brightness/contrast
            val cm = ColorMatrix()
            FilterPresets.matrices[state.filter]?.let { preset ->
                cm.postConcat(ColorMatrix(preset))
            }
            cm.postConcat(ColorMatrix().also { it.setSaturation(state.saturation) })
            cm.postConcat(ColorMatrix(floatArrayOf(
                state.contrast, 0f, 0f, 0f, state.brightness * 255f,
                0f, state.contrast, 0f, 0f, state.brightness * 255f,
                0f, 0f, state.contrast, 0f, state.brightness * 255f,
                0f, 0f, 0f, 1f, 0f,
            )))

            val result = Bitmap.createBitmap(transformed.width, transformed.height, Bitmap.Config.ARGB_8888)
            AndroidCanvas(result).drawBitmap(transformed, 0f, 0f, Paint().apply {
                colorFilter = ColorMatrixColorFilter(cm)
            })
            val previous = currentBitmap
            currentBitmap = result
            withContext(Dispatchers.Main) { cropView.setImageBitmap(result) }
            if (previous !== src) previous?.recycle()
            if (transformed !== src) transformed.recycle()
        }
    }

    private fun applyCrop(onDone: (() -> Unit)? = null) {
        val progressBar = cropView.findViewById<View>(com.canhub.cropper.R.id.CropProgressBar)
        progressBar?.isInvisible = false
        snapshot(captureBitmap = true)
        lifecycleScope.launch(CoroutineExceptionHandler { _, t ->
            runOnUiThread {
                progressBar?.isInvisible = true
                Toast.makeText(this@EditActivity, "Crop failed: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }) {
            val cropped = withContext(Dispatchers.Default) { cropView.getCroppedImage() }
            progressBar?.isInvisible = true
            if (cropped != null) {
                val oldOriginal = originalBitmap
                val oldCurrent = currentBitmap
                originalBitmap = cropped
                currentBitmap = cropped
                state = EditState(filter = state.filter)
                withContext(Dispatchers.Main) {
                    cropView.setImageBitmap(cropped)
                    mode = Mode.NONE
                    cropRow.visibility = View.GONE
                    onDone?.invoke()
                }
                if (oldCurrent !== oldOriginal) oldCurrent?.recycle()
                oldOriginal?.recycle()
            } else {
                withContext(Dispatchers.Main) { onDone?.invoke() }
            }
        }
    }

    private fun applyDraw() {
        val baked = drawView.bake() ?: return
        snapshot(captureBitmap = true)
        val oldOriginal = originalBitmap
        val oldCurrent = currentBitmap
        originalBitmap = baked
        currentBitmap = baked
        // Reset since we re-baked the bitmap
        state = EditState(filter = state.filter)
        cropView.setImageBitmap(baked)
        mode = Mode.NONE
        drawRow.visibility = View.GONE
        drawView.visibility = View.GONE
        drawView.clear()
        if (oldCurrent !== oldOriginal) oldCurrent?.recycle()
        oldOriginal?.recycle()
    }

    // ── Undo / redo ────────────────────────────────────────────────────────

    /**
     * Push a history entry. When [captureBitmap] is true a copy of [originalBitmap]
     * is stored so a future undo can restore the pre-operation pixels (used by crop
     * and draw, which replace [originalBitmap]).
     */
    private fun snapshot(captureBitmap: Boolean = false) {
        val snap = if (captureBitmap) safeCopy(originalBitmap) else null
        history.addLast(HistoryEntry(state, snap))
        while (history.size > historyLimit) history.removeFirst().baseSnapshot?.recycle()
        redoStack.forEach { it.baseSnapshot?.recycle() }
        redoStack.clear()
        updateHistoryButtons()
    }

    private fun undo() {
        if (history.isEmpty()) return
        val popped = history.removeLast()
        val redoBitmap = if (popped.baseSnapshot != null) safeCopy(originalBitmap) else null
        redoStack.addLast(HistoryEntry(state, redoBitmap))
        applyHistoryEntry(popped)
        updateHistoryButtons()
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        val popped = redoStack.removeLast()
        val historyBitmap = if (popped.baseSnapshot != null) safeCopy(originalBitmap) else null
        history.addLast(HistoryEntry(state, historyBitmap))
        applyHistoryEntry(popped)
        updateHistoryButtons()
    }

    private fun applyHistoryEntry(entry: HistoryEntry) {
        state = entry.state
        if (entry.baseSnapshot != null) {
            // Swap the bitmap and let applyAdjustments re-render with the restored state.
            val old = originalBitmap
            originalBitmap = entry.baseSnapshot
            // currentBitmap will be replaced by applyAdjustments below. Recycle the old
            // working buffer if it differs from the snapshot we just swapped in.
            if (old !== entry.baseSnapshot) old?.recycle()
            if (currentBitmap !== entry.baseSnapshot && currentBitmap !== originalBitmap) {
                currentBitmap?.recycle()
            }
            currentBitmap = entry.baseSnapshot
        }
        applyAdjustments()
    }

    private fun safeCopy(bm: Bitmap?): Bitmap? = bm?.runCatching {
        copy(config ?: Bitmap.Config.ARGB_8888, false)
    }?.getOrNull()

    private fun updateHistoryButtons() {
        undoBtn.setTextColor(if (history.isNotEmpty()) 0xFFFFFFFF.toInt() else 0xFF444444.toInt())
        redoBtn.setTextColor(if (redoStack.isNotEmpty()) 0xFFFFFFFF.toInt() else 0xFF444444.toInt())
        undoBtn.isEnabled = history.isNotEmpty()
        redoBtn.isEnabled = redoStack.isNotEmpty()
    }

    override fun onDestroy() {
        super.onDestroy()
        adjustJob?.cancel()
        if (currentBitmap !== originalBitmap) currentBitmap?.recycle()
        originalBitmap?.recycle()
        originalBitmap = null
        currentBitmap = null
        history.forEach { it.baseSnapshot?.recycle() }; history.clear()
        redoStack.forEach { it.baseSnapshot?.recycle() }; redoStack.clear()
    }

    // ── Saving ─────────────────────────────────────────────────────────────

    private fun saveAsCopy() {
        val bm = currentBitmap ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "gal_edit_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Gal")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    bm.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(it, cv, null, null)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@EditActivity, "Saved to Pictures/Gal", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /** Request write access to the source URI, then overwrite it. */
    private fun saveInPlaceRequest() {
        // Bitmap.compress can't encode HEIC/HEIF — overwriting in place would silently
        // change the format and rot the file's extension. Refuse early.
        val mime = contentResolver.getType(sourceUri)?.lowercase().orEmpty()
        if (mime == "image/heic" || mime == "image/heif") {
            Toast.makeText(
                this,
                "HEIC files can't be overwritten. Use Save copy instead.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val pi = MediaStore.createWriteRequest(contentResolver, listOf(sourceUri))
        writeLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    private fun saveInPlaceAfterPermission() {
        val bm = currentBitmap ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                contentResolver.openOutputStream(sourceUri, "wt")?.use { out ->
                    val mime = contentResolver.getType(sourceUri)?.lowercase() ?: "image/jpeg"
                    val format = when (mime) {
                        "image/png" -> Bitmap.CompressFormat.PNG
                        "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSLESS
                        else -> Bitmap.CompressFormat.JPEG
                    }
                    bm.compress(format, 95, out)
                } != null
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@EditActivity,
                    if (ok) "Saved" else "Save failed",
                    Toast.LENGTH_SHORT,
                ).show()
                if (ok) finish()
            }
        }
    }

    // ── UI helpers ─────────────────────────────────────────────────────────

    private fun cropPresetBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0x33FFFFFF)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, dp(4), 0) }
        setOnClickListener { onClick() }
    }

    private fun colorSwatch(color: Int, onClick: () -> Unit): View = View(this).apply {
        val size = dp(28)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            setMargins(dp(2), 0, dp(2), 0)
        }
        setBackgroundColor(color)
        setOnClickListener { onClick() }
    }
}

/** Color matrices for preset filters. Identity entries omitted by [matrices][None]. */
private object FilterPresets {
    val names = listOf("None", "B&W", "Sepia", "Vintage", "Vivid", "Cool", "Warm")

    val matrices: Map<String, FloatArray> = mapOf(
        "None" to identity(),
        "B&W" to floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
        "Sepia" to floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
        "Vintage" to floatArrayOf(
            0.9f, 0.5f, 0.1f, 0f, 0f,
            0.3f, 0.8f, 0.1f, 0f, 0f,
            0.2f, 0.3f, 0.5f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
        "Vivid" to floatArrayOf(
            1.4f, -0.2f, -0.2f, 0f, 0f,
            -0.2f, 1.4f, -0.2f, 0f, 0f,
            -0.2f, -0.2f, 1.4f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
        "Cool" to floatArrayOf(
            0.9f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1.2f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
        "Warm" to floatArrayOf(
            1.2f, 0f, 0f, 0f, 0f,
            0f, 1.05f, 0f, 0f, 0f,
            0f, 0f, 0.85f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

/**
 * Overlay view that accepts finger strokes and bakes them onto the base bitmap when applied.
 * Maintains its own scale/translate so the strokes line up with the displayed image area.
 */
private class DrawOverlayView(context: Context) : View(context) {
    var color: Int = AColor.WHITE
    var strokeWidth: Float = 14f

    private var base: Bitmap? = null
    private val strokes = mutableListOf<Stroke>()
    private var current: Stroke? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val srcRect = android.graphics.RectF()
    private val dstRect = android.graphics.RectF()

    private data class Stroke(val path: Path, val color: Int, val width: Float)

    fun attach(bm: Bitmap) {
        base = bm
        invalidate()
    }

    fun clear() {
        strokes.clear()
        current = null
        invalidate()
    }

    /** Returns a new bitmap with the strokes drawn over the base, or null. */
    fun bake(): Bitmap? {
        val src = base ?: return null
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = AndroidCanvas(out)
        // Strokes are in view coordinates; map to bitmap coordinates.
        if (dstRect.width() <= 0 || dstRect.height() <= 0) return out
        val sx = src.width / dstRect.width()
        val sy = src.height / dstRect.height()
        val m = Matrix().apply {
            postTranslate(-dstRect.left, -dstRect.top)
            postScale(sx, sy)
        }
        for (s in strokes) {
            val mapped = Path(s.path).apply { transform(m) }
            paint.color = s.color
            paint.strokeWidth = s.width * sx
            canvas.drawPath(mapped, paint)
        }
        return out
    }

    private fun computeDst() {
        val bm = base ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw == 0f || vh == 0f) return
        val scale = min(vw / bm.width, vh / bm.height)
        val dw = bm.width * scale; val dh = bm.height * scale
        val dx = (vw - dw) / 2f;   val dy = (vh - dh) / 2f
        srcRect.set(0f, 0f, bm.width.toFloat(), bm.height.toFloat())
        dstRect.set(dx, dy, dx + dw, dy + dh)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeDst()
    }

    override fun onDraw(canvas: AndroidCanvas) {
        super.onDraw(canvas)
        if (base == null) return
        computeDst()
        // Clip strokes to the displayed image area so they can't bleed outside.
        canvas.save()
        canvas.clipRect(dstRect)
        for (s in strokes) {
            paint.color = s.color
            paint.strokeWidth = s.width
            canvas.drawPath(s.path, paint)
        }
        current?.let { s ->
            paint.color = s.color
            paint.strokeWidth = s.width
            canvas.drawPath(s.path, paint)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        computeDst()
        if (dstRect.isEmpty || !dstRect.contains(event.x, event.y)) {
            // Reject touches outside the image — let parent handle them.
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = Stroke(Path().apply { moveTo(event.x, event.y) }, color, strokeWidth)
            }
            MotionEvent.ACTION_MOVE -> {
                current?.path?.lineTo(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { strokes.add(it) }
                current = null
                invalidate()
            }
            else -> return false
        }
        return true
    }

    @Suppress("unused")
    private fun unusedPorterDuff() = PorterDuff.Mode.SRC_OVER
}
