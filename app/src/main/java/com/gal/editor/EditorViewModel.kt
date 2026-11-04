package com.gal.editor

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max

enum class EditorTool(val label: String) {
    PRESETS("Presets"), LIGHT("Light"), CURVE("Curve"), COLOUR("Colour"), DETAIL("Detail"), CROP("Crop"),
    FILM("Film"), CAMERA("Camera"), EFFECTS("Effects"), FRAMES("Frames"), LOCAL("Local"),
}

/**
 * The editor's state: the photo open in the engine, the edit ([doc]) with undo and redo, the preview,
 * and saving. Every engine call runs on [native], one thread, in order, so renders never overlap and the
 * photo is only freed after the last of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModel(app: Application, savedState: SavedStateHandle) : AndroidViewModel(app) {

    val sourceUri: Uri = Uri.parse(checkNotNull(savedState.get<String>(EXTRA_URI)) { "no photo to edit" })

    private val native = Dispatchers.Default.limitedParallelism(1)
    private val resolver = app.contentResolver
    private val workDir = File(app.cacheDir, "editor")
    private var handle = 0L

    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var saving by mutableStateOf(false); private set
    var done by mutableStateOf(false); private set

    var doc by mutableStateOf<EditDoc?>(null); private set
    var preview by mutableStateOf<ImageBitmap?>(null); private set
    var before by mutableStateOf<ImageBitmap?>(null); private set
    var tool by mutableStateOf(EditorTool.LIGHT); private set
    /** The crop tool's aspect (pixels w/h): 0 free, -1 the frame's own. */
    var cropAspect by mutableStateOf(0f)

    var isRaw by mutableStateOf(false); private set
    var asShotKelvin by mutableStateOf<Float?>(null); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    /** Engine format for writing back over the original (0 JPEG, 1 PNG, 2 TIFF), or null when it can't be. */
    private var inPlaceFormat: Int? = null
    val canSaveInPlace get() = inPlaceFormat != null

    val catalog: EditorCatalog get() = EditorCatalog.instance

    private val undoStack = ArrayDeque<EditDoc>()
    private val redoStack = ArrayDeque<EditDoc>()
    private var committed: EditDoc? = null

    private data class RenderRequest(val json: String, val long: Int)
    private val renders = MutableStateFlow<RenderRequest?>(null)

    private val previewLong: Int = app.resources.displayMetrics.let {
        max(it.widthPixels, it.heightPixels).coerceIn(1080, 2048)
    }

    init {
        viewModelScope.launch {
            try {
                val (staged, format) = withContext(Dispatchers.IO) { stage() }
                inPlaceFormat = format
                val info = withContext(native) {
                    handle = GrainhouseNative.open(staged.absolutePath)
                    JSONObject(GrainhouseNative.info(handle))
                }
                isRaw = info.getBoolean("raw")
                asShotKelvin = info.optDouble("asShotKelvin").takeIf { !it.isNaN() }?.toFloat()
                if (isRaw) inPlaceFormat = null
                val start = withContext(native) { catalog.defaults }
                doc = start
                committed = start
                requestRender()
                loading = false
            } catch (e: Exception) {
                error = e.message ?: "Couldn't open this photo"
                loading = false
            }
        }
        viewModelScope.launch {
            renders.filterNotNull().collect { req ->
                val bmp = runCatching { withContext(native) { renderBitmap(req.json, req.long) } }
                bmp.onSuccess { preview = it }.onFailure { message = it.message ?: "Preview failed" }
            }
        }
    }

    // ── Editing ─────────────────────────────────────────────────────────────

    /** Changes the edit. Sliders pass commit = false while dragging and call [commit] when let go. */
    fun update(next: EditDoc, commit: Boolean = true) {
        doc = next
        requestRender()
        if (commit) commit()
    }

    fun set(path: String, value: Any?, commit: Boolean = true) {
        doc?.let { update(it.with(path, value), commit) }
    }

    fun commit() {
        val d = doc ?: return
        val c = committed
        if (c != null && c != d) {
            undoStack.addLast(c)
            if (undoStack.size > HISTORY) undoStack.removeFirst()
            redoStack.clear()
        }
        committed = d
        refreshHistory()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        doc?.let { redoStack.addLast(it) }
        doc = prev
        committed = prev
        refreshHistory()
        requestRender()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        doc?.let { undoStack.addLast(it) }
        doc = next
        committed = next
        refreshHistory()
        requestRender()
    }

    fun reset() {
        update(catalog.defaults)
    }

    fun applyPreset(preset: EditorCatalog.Preset) {
        val d = doc ?: return
        viewModelScope.launch {
            val out = runCatching { withContext(native) { GrainhouseNative.applyPreset(d.json, preset.settings) } }
            out.onSuccess { update(EditDoc.parse(it)) }.onFailure { message = it.message }
        }
    }

    fun autoLight() {
        val d = doc ?: return
        val raw = d.obj("raw")?.toString() ?: return
        viewModelScope.launch {
            val out = runCatching { withContext(native) { GrainhouseNative.autoLight(handle, raw) } }
            out.onSuccess { update(d.with("raw", JSONObject(it))) }.onFailure { message = it.message }
        }
    }

    /** White balance in kelvin as the sliders show it: the set value, or the photo's own. */
    fun kelvin(d: EditDoc): Float =
        if (d.isNull("raw.kelvin")) (asShotKelvin ?: if (isRaw) 5500f else JPEG_KELVIN) else d.float("raw.kelvin")

    fun setKelvin(k: Float?, commit: Boolean) {
        // JPEGs are left alone at their neutral 6500 K, as the desktop app does
        val value = if (k != null && !isRaw && abs(k - JPEG_KELVIN) < 1f) null else k
        set("raw.kelvin", value, commit)
    }

    fun clearMessage() {
        message = null
    }

    // ── Preview ─────────────────────────────────────────────────────────────

    private fun requestRender() {
        val d = doc ?: return
        renders.value = RenderRequest(previewDoc(d).json, previewLong)
    }

    /** The crop tool edits the crop over the whole (turned, straightened) frame, without a border. */
    private fun previewDoc(d: EditDoc): EditDoc =
        if (tool == EditorTool.CROP) uncropped(d) else d

    private fun uncropped(d: EditDoc) =
        d.with("geometry.x", 0f).with("geometry.y", 0f).with("geometry.w", 1f).with("geometry.h", 1f).with("frame", 0)

    fun selectTool(t: EditorTool) {
        if (t == tool) return
        val wasCrop = tool == EditorTool.CROP
        tool = t
        if (wasCrop || t == EditorTool.CROP) requestRender()
    }

    /** The photo as it came, framed like the edit, for press-and-hold compare. */
    fun showBefore(show: Boolean) {
        if (!show) {
            before = null
            return
        }
        val d = doc ?: return
        val base = catalog.defaults.with("geometry", d.obj("geometry"))
        viewModelScope.launch {
            runCatching { withContext(native) { renderBitmap(previewDoc(base).json, previewLong) } }
                .onSuccess { before = it }
        }
    }

    private fun renderBitmap(json: String, long: Int): ImageBitmap {
        val px = GrainhouseNative.render(handle, json, long)
        val w = px[0]
        val h = px[1]
        return Bitmap.createBitmap(px, 2, w, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    // ── Opening ─────────────────────────────────────────────────────────────

    /**
     * Copies the photo into the cache for the engine, which reads files. Formats it can't decode
     * (HEIC, WebP, AVIF…) go through Android's decoder into a lossless PNG first. Returns the file and
     * the engine format for saving back over the original, if that is possible.
     */
    private fun stage(): Pair<File, Int?> {
        workDir.deleteRecursively()
        workDir.mkdirs()
        val mime = resolver.getType(sourceUri)?.lowercase().orEmpty()
        val name = resolver.query(sourceUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
        val engineReads = mime in ENGINE_MIMES || ext in RAW_EXTENSIONS
        val inPlace = if (sourceUri.authority == MediaStore.AUTHORITY) IN_PLACE_FORMATS[mime] else null
        if (engineReads) {
            val out = File(workDir, "source.${ext.ifEmpty { ENGINE_MIMES[mime] ?: "jpg" }}")
            resolver.openInputStream(sourceUri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                ?: error("Couldn't read this photo")
            return out to inPlace
        }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, sourceUri)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = File(workDir, "source.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return out to null
    }

    // ── Saving ──────────────────────────────────────────────────────────────

    /** Writes the edit over the original. Call after the user granted write access (createWriteRequest). */
    fun saveInPlace() {
        val format = inPlaceFormat ?: return
        save {
            val file = export(format)
            val out = resolver.openOutputStream(sourceUri, "wt") ?: error("Couldn't open the photo for writing")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            // bump the modified time so the library (and image caches keyed on it) refresh
            runCatching {
                resolver.update(
                    sourceUri,
                    ContentValues().apply { put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000) },
                    null, null,
                )
            }
            "Saved"
        }
    }

    /** Saves the edit as a new JPEG in Pictures/Gal, leaving the original alone. */
    fun saveCopy() {
        save {
            val file = export(0)
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "gal_edit_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Gal")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: error("Couldn't create the copy")
            try {
                val out = resolver.openOutputStream(uri) ?: error("Couldn't write the copy")
                out.use { o -> file.inputStream().use { it.copyTo(o) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            "Saved to Pictures/Gal"
        }
    }

    fun reportWriteDenied(reason: String? = null) {
        message = reason?.let { "Couldn't request write access: $it" } ?: "Save cancelled"
    }

    private fun save(block: suspend () -> String) {
        if (saving || doc == null) return
        saving = true
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            saving = false
            result.onSuccess { message = it; done = true }
                .onFailure { message = "Save failed: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    /** The edit at full size, encoded by the engine into the cache. */
    private suspend fun export(format: Int): File {
        val d = checkNotNull(doc)
        val out = File(workDir, "export.${listOf("jpg", "png", "tif")[format]}")
        withContext(native) { GrainhouseNative.export(handle, d.json, out.absolutePath, format, JPEG_QUALITY) }
        return out
    }

    private fun refreshHistory() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    override fun onCleared() {
        val h = handle
        handle = 0L
        // queued behind any render still running on the engine thread
        CoroutineScope(native + NonCancellable).launch {
            if (h != 0L) runCatching { GrainhouseNative.close(h) }
            workDir.deleteRecursively()
        }
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
        private const val HISTORY = 50
        private const val JPEG_QUALITY = 95
        private const val JPEG_KELVIN = 6500f

        private val ENGINE_MIMES = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/tiff" to "tif")
        private val IN_PLACE_FORMATS = mapOf("image/jpeg" to 0, "image/png" to 1, "image/tiff" to 2)
        private val RAW_EXTENSIONS = setOf(
            "arw", "srf", "sr2", "dng", "cr2", "cr3", "nef", "nrw", "raf", "orf", "rw2", "pef", "srw", "3fr", "iiq", "erf", "mos",
        )
    }
}
