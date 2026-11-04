package com.gal.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onBack: () -> Unit,
    onSaveInPlace: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var exportSheet by remember { mutableStateOf(false) }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = vm::redo, enabled = vm.canRedo) {
                        Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo")
                    }
                    TextButton(onClick = { exportSheet = true }, enabled = vm.doc != null && !vm.saving) { Text("Save copy") }
                    if (vm.canSaveInPlace) {
                        Button(
                            onClick = { confirmOverwrite = true },
                            enabled = vm.doc != null && !vm.saving,
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text("Save") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    vm.loading -> CircularProgressIndicator()
                    vm.error != null -> Text(
                        vm.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                    else -> PreviewArea(vm)
                }
                if (vm.saving) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                    ) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.width(16.dp))
                            Text("Saving…")
                        }
                    }
                }
            }
            val doc = vm.doc
            if (doc != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.navigationBarsPadding()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 8.dp),
                        ) {
                            ToolPanel(vm, doc)
                        }
                        PrimaryScrollableTabRow(
                            selectedTabIndex = vm.tool.ordinal,
                            edgePadding = 8.dp,
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            EditorTool.entries.forEach { t ->
                                Tab(
                                    selected = vm.tool == t,
                                    onClick = { vm.selectTool(t) },
                                    text = { Text(t.label) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (exportSheet) {
        ExportSheet(vm, onDismiss = { exportSheet = false })
    }

    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("Overwrite original?") },
            text = { Text("The original file will be replaced with your edits. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmOverwrite = false; onSaveInPlace() }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverwrite = false }) { Text("Cancel") }
            },
        )
    }
}

// ── Save copy ───────────────────────────────────────────────────────────────

/** Format, size, sharpening and metadata for a copy; the original is never touched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    val o = vm.exportOptions
    fun set(next: ExportOptions) { vm.exportOptions = next }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text("Save a copy", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                "Saved as a new photo in Pictures/Gal. The original stays as it is.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            SectionLabel("Format")
            Segmented(ExportFormat.entries.map { it.label }, o.format.ordinal) { set(o.copy(format = ExportFormat.entries[it])) }
            if (o.format == ExportFormat.JPEG) {
                ValueSlider(
                    "Quality", o.quality.toFloat(), 50f, 100f, 0, "%",
                    onChange = { set(o.copy(quality = it.roundToInt())) }, onDone = {}, onReset = { set(o.copy(quality = 92)) },
                )
            }

            SectionLabel("Size")
            val original = vm.photoSize?.let { (w, h) -> "Original (${w} × ${h})" } ?: "Original"
            Segmented(listOf("Original", "Long edge", "Percent"), o.size) { set(o.copy(size = it)) }
            when (o.size) {
                0 -> Text(original, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                1 -> ValueSlider(
                    "Long edge", o.longEdge.toFloat(), 500f, 8000f, 0, " px",
                    onChange = { set(o.copy(longEdge = (it / 50f).roundToInt() * 50)) }, onDone = {},
                    onReset = { set(o.copy(longEdge = 3000)) },
                )
                else -> ValueSlider(
                    "Scale", o.percent.toFloat(), 10f, 100f, 0, "%",
                    onChange = { set(o.copy(percent = it.roundToInt())) }, onDone = {}, onReset = { set(o.copy(percent = 50)) },
                )
            }

            SectionLabel("Output")
            SwitchRow("Sharpen for output", "Smart sharpening after resizing", o.sharpen) { set(o.copy(sharpen = it)) }
            if (o.sharpen) {
                ValueSlider(
                    "Amount", o.sharpenAmount * 100f, 0f, 200f, 0, "",
                    onChange = { set(o.copy(sharpenAmount = it / 100f)) }, onDone = {}, onReset = { set(o.copy(sharpenAmount = 0.8f)) },
                )
            }
            SwitchRow(
                "Keep photo info",
                if (o.format == ExportFormat.JPEG) "Camera, date and location (EXIF)" else "Only JPEG copies carry photo info",
                o.keepMetadata && o.format == ExportFormat.JPEG,
            ) { set(o.copy(keepMetadata = it)) }

            Button(
                onClick = { vm.saveCopy(); onDismiss() },
                enabled = !vm.saving,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) { Text("Save copy") }
        }
    }
}

@Composable
private fun Segmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        items.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = items.size),
            ) { Text(label) }
        }
    }
}

// ── Preview ─────────────────────────────────────────────────────────────────

/** The photo; hold to see the original. In the crop tool, the whole frame with the crop drawn over it. */
@Composable
private fun PreviewArea(vm: EditorViewModel) {
    val image = vm.before ?: vm.preview ?: run {
        CircularProgressIndicator()
        return
    }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val cropping = vm.tool == EditorTool.CROP
    Box(
        Modifier
            .fillMaxSize()
            .padding(12.dp)
            .onSizeChanged { box = it }
            .pointerInput(cropping) {
                if (!cropping) {
                    detectTapGestures(onPress = {
                        vm.showBefore(true)
                        tryAwaitRelease()
                        vm.showBefore(false)
                    })
                }
            },
    ) {
        Image(
            bitmap = image,
            contentDescription = "Photo being edited",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (cropping && box != IntSize.Zero) {
            val scale = min(box.width / image.width.toFloat(), box.height / image.height.toFloat())
            val w = image.width * scale
            val h = image.height * scale
            val frame = Rect(Offset((box.width - w) / 2f, (box.height - h) / 2f), Size(w, h))
            CropOverlay(vm, frame, image.width.toFloat() / image.height)
        }
    }
}

/** Crop aspect choices: label and width/height, 0 = free, -1 = the frame's own. */
private val ASPECTS = listOf(
    "Free" to 0f, "Original" to -1f, "1:1" to 1f, "3:2" to 1.5f, "2:3" to 2f / 3, "4:3" to 4f / 3, "3:4" to 0.75f,
    "16:9" to 16f / 9, "9:16" to 9f / 16, "5:4" to 1.25f, "XPan" to 65f / 24,
)

/** The crop as fractions of the frame; drag a corner to resize, inside to move. */
@Composable
private fun CropOverlay(vm: EditorViewModel, frame: Rect, frameAspect: Float) {
    val doc by rememberUpdatedState(vm.doc)
    val aspect by rememberUpdatedState(vm.cropAspect.let { if (it < 0f) frameAspect else it })
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(frame) {
                var mode = -1 // 0..3 corners (tl, tr, br, bl), 4 move
                detectDragGestures(
                    onDragStart = { p ->
                        val d = doc ?: return@detectDragGestures
                        val r = cropRect(d, frame)
                        val corners = listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)
                        val near = corners.indexOfFirst { (it - p).getDistance() < 48.dp.toPx() }
                        mode = if (near >= 0) near else if (r.contains(p)) 4 else -1
                    },
                    onDragEnd = { if (mode >= 0) vm.commit(); mode = -1 },
                    onDragCancel = { if (mode >= 0) vm.commit(); mode = -1 },
                    onDrag = { change, drag ->
                        val d = doc ?: return@detectDragGestures
                        if (mode < 0) return@detectDragGestures
                        change.consume()
                        vm.update(dragCrop(d, mode, drag.x / frame.width, drag.y / frame.height, aspect, frameAspect), commit = false)
                    },
                )
            },
    ) {
        val d = doc ?: return@Canvas
        val r = cropRect(d, frame)
        val shade = Color.Black.copy(alpha = 0.55f)
        drawRect(shade, frame.topLeft, Size(frame.width, r.top - frame.top))
        drawRect(shade, Offset(frame.left, r.bottom), Size(frame.width, frame.bottom - r.bottom))
        drawRect(shade, Offset(frame.left, r.top), Size(r.left - frame.left, r.height))
        drawRect(shade, Offset(r.right, r.top), Size(frame.right - r.right, r.height))
        for (i in 1..2) {
            val x = r.left + r.width * i / 3
            val y = r.top + r.height * i / 3
            drawLine(Color.White.copy(alpha = 0.4f), Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.4f), Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
        }
        drawRect(Color.White, r.topLeft, r.size, style = Stroke(1.5.dp.toPx()))
        val handle = 6.dp.toPx()
        listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft).forEach { drawCircle(accent, handle, it) }
    }
}

private fun cropRect(d: EditDoc, frame: Rect) = Rect(
    Offset(frame.left + d.float("geometry.x") * frame.width, frame.top + d.float("geometry.y") * frame.height),
    Size(d.float("geometry.w") * frame.width, d.float("geometry.h") * frame.height),
)

private const val MIN_CROP = 0.05f

/** Moves a corner (or the whole crop) by (dx, dy) fractions, keeping [aspect] (pixels w/h) unless it's 0. */
private fun dragCrop(d: EditDoc, mode: Int, dx: Float, dy: Float, aspect: Float, frameAspect: Float): EditDoc {
    var x = d.float("geometry.x")
    var y = d.float("geometry.y")
    var w = d.float("geometry.w")
    var h = d.float("geometry.h")
    if (mode == 4) {
        x = (x + dx).coerceIn(0f, 1f - w)
        y = (y + dy).coerceIn(0f, 1f - h)
    } else {
        // the corner opposite the dragged one stays put
        val left = mode == 0 || mode == 3
        val top = mode == 0 || mode == 1
        val ax = if (left) x + w else x
        val ay = if (top) y + h else y
        var nw = (w + if (left) -dx else dx).coerceIn(MIN_CROP, if (left) ax else 1f - ax)
        var nh = (h + if (top) -dy else dy).coerceIn(MIN_CROP, if (top) ay else 1f - ay)
        if (aspect > 0f) {
            // fractions to pixels: w * frameAspect / h = aspect
            val maxH = if (top) ay else 1f - ay
            nh = (nw * frameAspect / aspect).coerceAtMost(maxH)
            nw = nh * aspect / frameAspect
        }
        w = nw
        h = nh
        x = if (left) ax - w else ax
        y = if (top) ay - h else ay
    }
    return d.with("geometry.x", x).with("geometry.y", y).with("geometry.w", w).with("geometry.h", h)
}

/** The largest crop of [aspect] (pixels w/h) centred in the frame. */
private fun centredCrop(d: EditDoc, aspect: Float, frameAspect: Float): EditDoc {
    var w = 1f
    var h = frameAspect / aspect
    if (h > 1f) {
        w = 1f / h
        h = 1f
    }
    return d.with("geometry.x", (1f - w) / 2).with("geometry.y", (1f - h) / 2).with("geometry.w", w).with("geometry.h", h)
}

private fun fullCrop(d: EditDoc) =
    d.with("geometry.x", 0f).with("geometry.y", 0f).with("geometry.w", 1f).with("geometry.h", 1f)

// ── Tools ───────────────────────────────────────────────────────────────────

@Composable
private fun ToolPanel(vm: EditorViewModel, d: EditDoc) {
    val c = vm.catalog
    when (vm.tool) {
        EditorTool.PRESETS -> {
            ChoiceRow(c.presets.map { it.name }, selected = -1) { vm.applyPreset(c.presets[it]) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(onClick = vm::reset) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset all edits")
                }
            }
        }

        EditorTool.LIGHT -> {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = vm::autoLight) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Auto")
                }
            }
            val wb = c.whiteBalance.filter { it.name != "Custom" }
            val kelvinSet = !d.isNull("raw.kelvin")
            ChoiceRow(
                wb.map { it.name },
                selected = wb.indexOfFirst { if (it.kelvin == 0f) !kelvinSet else kelvinSet && abs(d.float("raw.kelvin") - it.kelvin) < 1f },
            ) { i -> vm.setKelvin(wb[i].kelvin.takeIf { it > 0f }, commit = true) }
            ValueSlider(
                "Temperature", vm.kelvin(d), 2000f, 12000f, decimals = 0, suffix = " K",
                onChange = { vm.setKelvin(it, commit = false) }, onDone = vm::commit,
                onReset = { vm.setKelvin(null, commit = true) },
            )
            PathSlider(vm, d, "Tint", "raw.tint", -100f, 100f)
            PathSlider(vm, d, "Exposure", "raw.exposure", -3f, 3f, scale = 1f, decimals = 2)
            PathSlider(vm, d, "Contrast", "raw.contrast", 0f, 200f)
            PathSlider(vm, d, "Highlights", "raw.highlights", -100f, 100f)
            PathSlider(vm, d, "Shadows", "raw.shadows", -100f, 100f)
            PathSlider(vm, d, "Whites", "raw.whites", -100f, 100f)
            PathSlider(vm, d, "Blacks", "raw.blacks", -100f, 100f)
        }

        EditorTool.CURVE -> {
            var channel by remember { mutableIntStateOf(0) }
            ChoiceRow(c.curveChannels, channel) { channel = it }
            ToneCurve(vm, d, channel, c.curveX)
        }

        EditorTool.COLOUR -> {
            PathSlider(vm, d, "Vibrance", "adjust.vibrance", -100f, 100f)
            PathSlider(vm, d, "Saturation", "adjust.saturation", -100f, 100f)
            SectionLabel("Colour mixer")
            var mix by remember { mutableIntStateOf(0) }
            ChoiceRow(c.mixer, mix) { mix = it }
            PathSlider(vm, d, "Hue", "adjust.mixer.$mix.0", -100f, 100f)
            PathSlider(vm, d, "Saturation", "adjust.mixer.$mix.1", -100f, 100f)
            PathSlider(vm, d, "Luminance", "adjust.mixer.$mix.2", -100f, 100f)
            SectionLabel("Colour grading")
            var zone by remember { mutableIntStateOf(0) }
            ChoiceRow(c.grades, zone) { zone = it }
            PathSlider(vm, d, "Hue", "adjust.grade.$zone.0", 0f, 360f, scale = 1f, suffix = "°")
            PathSlider(vm, d, "Amount", "adjust.grade.$zone.1", 0f, 100f)
            PathSlider(vm, d, "Balance", "adjust.balance", -100f, 100f)
        }

        EditorTool.DETAIL -> {
            PathSlider(vm, d, "Clarity", "adjust.clarity", -100f, 100f)
            PathSlider(vm, d, "Texture", "adjust.texture", -100f, 100f)
            PathSlider(vm, d, "Dehaze", "adjust.dehaze", -100f, 100f)
            SectionLabel("Sharpening")
            PathSlider(vm, d, "Sharpen", "adjust.sharpen", 0f, 100f)
            PathSlider(vm, d, "Radius", "adjust.sharpen_radius", 0.5f, 3f, scale = 1f, decimals = 1)
            PathSlider(vm, d, "Masking", "adjust.sharpen_masking", 0f, 100f)
            SectionLabel("Noise reduction")
            PathSlider(vm, d, "Luminance", "adjust.noise", 0f, 100f)
            PathSlider(vm, d, "Colour", "adjust.colour_noise", 0f, 100f)
            SectionLabel("Vignette")
            PathSlider(vm, d, "Amount", "adjust.vignette", -100f, 100f)
            PathSlider(vm, d, "Midpoint", "adjust.vignette_mid", 0f, 100f)
            PathSlider(vm, d, "Feather", "adjust.vignette_feather", 0f, 100f)
            PathSlider(vm, d, "Defringe", "adjust.defringe", 0f, 100f)
        }

        EditorTool.CROP -> {
            val frameAspect = vm.preview?.let { it.width.toFloat() / it.height } ?: 1f
            ChoiceRow(ASPECTS.map { it.first }, ASPECTS.indexOfFirst { it.second == vm.cropAspect }) { i ->
                val a = ASPECTS[i].second
                vm.cropAspect = a
                vm.update(if (a == 0f) d else centredCrop(d, if (a < 0f) frameAspect else a, frameAspect))
            }
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.update(fullCrop(d.with("geometry.quarter", (d.int("geometry.quarter") + 3) % 4))) }) {
                    Icon(Icons.AutoMirrored.Outlined.RotateLeft, contentDescription = "Rotate left")
                }
                IconButton(onClick = { vm.update(fullCrop(d.with("geometry.quarter", (d.int("geometry.quarter") + 1) % 4))) }) {
                    Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = "Rotate right")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.cropAspect = 0f; vm.update(fullCrop(d)) }) { Text("Reset crop") }
            }
            PathSlider(vm, d, "Straighten", "geometry.angle", -20f, 20f, scale = 1f, decimals = 1, suffix = "°")
            SectionLabel("Lens")
            PathSlider(vm, d, "Distortion", "geometry.distortion", -100f, 100f)
            PathSlider(vm, d, "Vertical", "geometry.vertical", -100f, 100f)
            PathSlider(vm, d, "Horizontal", "geometry.horizontal", -100f, 100f)
        }

        EditorTool.FILM -> {
            val current = d.string("film")
            val groups = listOf("None") + c.brands + "Creative"
            var group by remember {
                mutableStateOf(
                    when {
                        current == null -> "None"
                        current.startsWith("gen:") -> "Creative"
                        else -> c.films.firstOrNull { it.file == current }?.brand ?: "None"
                    },
                )
            }
            ChoiceRow(groups, groups.indexOf(group)) { i ->
                group = groups[i]
                if (group == "None") vm.set("film", null)
            }
            if (group == "Creative") {
                ChoiceRow(c.creative, c.creative.indices.indexOfFirst { current == "gen:$it" }) { vm.set("film", "gen:$it") }
            } else if (group != "None") {
                val films = c.films.filter { it.brand == group }
                ChoiceRow(films.map { it.name }, films.indexOfFirst { it.file == current }) { vm.set("film", films[it].file) }
            }
            if (current != null) PathSlider(vm, d, "Strength", "strength", 0f, 100f)
        }

        EditorTool.CAMERA -> {
            ChoiceRow(c.cameras, d.int("camera")) { vm.set("camera", it) }
            if (d.int("camera") > 0) PathSlider(vm, d, "Strength", "camera_strength", 0f, 100f)
            SwitchRow("Date stamp", "The orange date in the corner, from when the photo was taken", d.bool("date_stamp")) {
                vm.set("date_stamp", it)
            }
        }

        EditorTool.EFFECTS -> {
            SectionLabel("Diffusion filter")
            ChoiceRow(c.diffusion, d.int("diffusion")) { vm.set("diffusion", it) }
            if (d.int("diffusion") > 0) {
                PathSlider(vm, d, "Strength", "diffusion_opt.strength", 0f, 200f)
                PathSlider(vm, d, "Glow size", "diffusion_opt.size", 25f, 300f)
                PathSlider(vm, d, "Highlight bloom", "diffusion_opt.bloom", 0f, 200f)
                PathSlider(vm, d, "Warmth", "diffusion_opt.warmth", -100f, 100f)
            }
            SectionLabel("Grain")
            ChoiceRow(c.grain, d.int("develop.grain")) { vm.set("develop.grain", it) }
            SwitchRow("Halation", "CineStill-style red glow round bright lights", d.bool("develop.halation")) { vm.set("develop.halation", it) }
            SwitchRow("Light leak", "Warm film light leaks at the edges", d.bool("develop.light_leak")) { vm.set("develop.light_leak", it) }
            SwitchRow("Monochrome grain", "Grain without colour, for black and white film", d.bool("develop.monochrome")) { vm.set("develop.monochrome", it) }
        }

        EditorTool.FRAMES -> ChoiceRow(c.frames, d.int("frame")) { vm.set("frame", it) }

        EditorTool.LOCAL -> {
            val kind = d.int("local.kind")
            ChoiceRow(c.localKinds.map { it.name }, c.localKinds.indexOfFirst { it.index == kind }) { vm.set("local.kind", c.localKinds[it].index) }
            if (kind != 0) {
                PathSlider(vm, d, "Size", "local.size", 0f, 100f)
                PathSlider(vm, d, "Feather", "local.feather", 0f, 100f)
                PathSlider(vm, d, "Exposure", "local.exposure", -3f, 3f, scale = 1f, decimals = 2)
                PathSlider(vm, d, "Contrast", "local.contrast", -100f, 100f)
                PathSlider(vm, d, "Saturation", "local.saturation", -100f, 100f)
                PathSlider(vm, d, "Warmth", "local.warmth", -100f, 100f)
            }
        }
    }
}

// ── Controls ────────────────────────────────────────────────────────────────

/** A slider for the setting at [path], shown as its value × [scale]. Tap the number to reset it. */
@Composable
private fun PathSlider(
    vm: EditorViewModel,
    d: EditDoc,
    label: String,
    path: String,
    min: Float,
    max: Float,
    scale: Float = 100f,
    decimals: Int = 0,
    suffix: String = "",
) {
    ValueSlider(
        label, d.float(path) * scale, min, max, decimals, suffix,
        onChange = { vm.set(path, it / scale, commit = false) },
        onDone = vm::commit,
        onReset = { vm.set(path, vm.catalog.defaults.float(path)) },
    )
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    decimals: Int,
    suffix: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                format(value, decimals) + suffix,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClickLabel = "Reset $label", onClick = onReset)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = min..max,
            modifier = Modifier.height(36.dp),
        )
    }
}

private fun format(v: Float, decimals: Int): String =
    if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(v)

@Composable
private fun ChoiceRow(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        itemsIndexed(items) { i, name ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(name) })
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** The tone curve: five points (shadows to highlights) for [channel]; drag a point up or down. */
@Composable
private fun ToneCurve(vm: EditorViewModel, d: EditDoc, channel: Int, xs: List<Float>) {
    val doc by rememberUpdatedState(d)
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val curveColour = when (channel) {
        1 -> Color(0xFFE5534B)
        2 -> Color(0xFF57AB5A)
        3 -> Color(0xFF539BF5)
        else -> line
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxWidth(0.8f)
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
                .pointerInput(channel) {
                    var point = -1
                    detectDragGestures(
                        onDragStart = { p ->
                            val px = p.x / size.width
                            point = xs.indices.minByOrNull { abs(xs[it] - px) } ?: -1
                        },
                        onDragEnd = { vm.commit(); point = -1 },
                        onDragCancel = { vm.commit(); point = -1 },
                        onDrag = { change, drag ->
                            if (point < 0) return@detectDragGestures
                            change.consume()
                            val path = "adjust.curve.$channel.$point"
                            val y = (doc.float(path) - drag.y / size.height).coerceIn(0f, 1f)
                            vm.set(path, y, commit = false)
                        },
                    )
                },
        ) {
            for (i in 1..3) {
                val t = i / 4f
                drawLine(grid, Offset(size.width * t, 0f), Offset(size.width * t, size.height), 1.dp.toPx())
                drawLine(grid, Offset(0f, size.height * t), Offset(size.width, size.height * t), 1.dp.toPx())
            }
            drawLine(grid, Offset(0f, size.height), Offset(size.width, 0f), 1.dp.toPx())
            val pts = xs.indices.map { i -> Offset(xs[i] * size.width, (1f - d.float("adjust.curve.$channel.$i")) * size.height) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]
                    val b = pts[i]
                    val mx = (a.x + b.x) / 2
                    cubicTo(mx, a.y, mx, b.y, b.x, b.y)
                }
            }
            drawPath(path, curveColour, style = Stroke(2.5.dp.toPx()))
            pts.forEach { drawCircle(curveColour, 7.dp.toPx(), it) }
        }
    }
}
