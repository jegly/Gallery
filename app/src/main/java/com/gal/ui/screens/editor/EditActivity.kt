package com.gal.ui.screens.editor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
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
import java.net.URLDecoder

class EditActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URI = "extra_uri"
        fun launch(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, EditActivity::class.java).putExtra(EXTRA_URI, uri.toString())
            )
        }
    }

    private enum class Mode { NONE, BRIGHTNESS, CONTRAST, SATURATION, CROP }

    private lateinit var sourceUri: Uri
    private lateinit var cropView: CropImageView
    private lateinit var sliderRow: LinearLayout
    private lateinit var sliderLabel: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var cropRow: LinearLayout

    private var mode = Mode.NONE
    private var brightness = 0f
    private var contrast = 1f
    private var saturation = 1f
    private var rotation = 0f
    private var originalBitmap: Bitmap? = null
    private var currentBitmap: Bitmap? = null
    private var adjustJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        toolbar.addView(Button(this).apply {
            text = "Save copy"
            setTextColor(0xFFCDB8FF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { saveImage() }
        })

        // ── CropImageView ─────────────────────────────────────────────────────
        cropView = CropImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            guidelines = CropImageView.Guidelines.ON
        }

        // ── Slider row ────────────────────────────────────────────────────────
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
                        Mode.BRIGHTNESS -> { brightness = (p - 100) / 100f; applyAdjustments() }
                        Mode.CONTRAST   -> { contrast   = (p / 100f).coerceAtLeast(0.01f); applyAdjustments() }
                        Mode.SATURATION -> { saturation = (p / 100f).coerceAtLeast(0f); applyAdjustments() }
                        else -> {}
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
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

        // ── Crop action row ───────────────────────────────────────────────────
        cropRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0A0A0A.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        cropRow.addView(Button(this).apply {
            text = "Cancel"
            setTextColor(0xFF888888.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                mode = Mode.NONE
                sliderRow.visibility = View.GONE
                cropRow.visibility = View.GONE
                currentBitmap?.let { cropView.setImageBitmap(it) }
            }
        })
        cropRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        cropRow.addView(Button(this).apply {
            text = "Apply crop"
            setTextColor(0xFFCDB8FF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { applyCrop() }
        })

        // ── Tool buttons ──────────────────────────────────────────────────────
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0A0A0A.toInt())
            gravity = Gravity.CENTER
        }

        fun toolBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 10f
            setTextColor(0xFF888888.toInt())
            setBackgroundColor(0x00000000)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

        toolRow.addView(toolBtn("☀\nBright") {
            mode = Mode.BRIGHTNESS
            sliderLabel.text = "Brightness"
            seekBar.progress = ((brightness * 100) + 100).toInt().coerceIn(0, 200)
            sliderRow.visibility = View.VISIBLE
            cropRow.visibility = View.GONE
        })
        toolRow.addView(toolBtn("◑\nContrast") {
            mode = Mode.CONTRAST
            sliderLabel.text = "Contrast"
            seekBar.progress = (contrast * 100).toInt().coerceIn(0, 200)
            sliderRow.visibility = View.VISIBLE
            cropRow.visibility = View.GONE
        })
        toolRow.addView(toolBtn("◎\nSat") {
            mode = Mode.SATURATION
            sliderLabel.text = "Saturation"
            seekBar.progress = (saturation * 100).toInt().coerceIn(0, 200)
            sliderRow.visibility = View.VISIBLE
            cropRow.visibility = View.GONE
        })
        toolRow.addView(toolBtn("⊡\nCrop") {
            mode = Mode.CROP
            sliderRow.visibility = View.GONE
            cropRow.visibility = View.VISIBLE
            cropView.guidelines = CropImageView.Guidelines.ON
        })
        toolRow.addView(toolBtn("↺\nRotate L") {
            rotation -= 90f
            applyAdjustments()
        })
        toolRow.addView(toolBtn("↻\nRotate R") {
            rotation += 90f
            applyAdjustments()
        })

        root.addView(toolbar)
        root.addView(cropView)
        root.addView(sliderRow)
        root.addView(cropRow)
        root.addView(toolRow)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

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
        // Cancel any in-flight adjustment so rapid slider moves don't pile up
        adjustJob?.cancel()
        adjustJob = lifecycleScope.launch(Dispatchers.Default) {
            val rotated = if (rotation % 360f != 0f)
                Bitmap.createBitmap(src, 0, 0, src.width, src.height,
                    Matrix().apply { postRotate(rotation) }, true)
            else src

            val result = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
            val cm = ColorMatrix()
            cm.postConcat(ColorMatrix().also { it.setSaturation(saturation) })
            cm.postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, brightness * 255f,
                0f, contrast, 0f, 0f, brightness * 255f,
                0f, 0f, contrast, 0f, brightness * 255f,
                0f, 0f, 0f, 1f, 0f,
            )))
            AndroidCanvas(result).drawBitmap(rotated, 0f, 0f, Paint().apply {
                colorFilter = ColorMatrixColorFilter(cm)
            })
            // Recycle the previous intermediate bitmap before replacing it
            val previous = currentBitmap
            currentBitmap = result
            withContext(Dispatchers.Main) { cropView.setImageBitmap(result) }
            if (previous !== src) previous?.recycle()
            if (rotated !== src) rotated.recycle()
        }
    }

    private fun applyCrop() {
        val progressBar = cropView.findViewById<View>(com.canhub.cropper.R.id.CropProgressBar)
        progressBar?.isInvisible = false

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
                withContext(Dispatchers.Main) {
                    cropView.setImageBitmap(cropped)
                    mode = Mode.NONE
                    cropRow.visibility = View.GONE
                }
                if (oldCurrent !== oldOriginal) oldCurrent?.recycle()
                oldOriginal?.recycle()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adjustJob?.cancel()
        if (currentBitmap !== originalBitmap) currentBitmap?.recycle()
        originalBitmap?.recycle()
        originalBitmap = null
        currentBitmap = null
    }

    private fun saveImage() {
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
}
