package com.gal.editor

/**
 * The editor engine: Grainhouse's editing library (native/editor, libgal_editor.so). Edits travel as
 * Grainhouse `Settings` JSON. Each call throws a RuntimeException with a readable message on failure.
 *
 * Not thread-safe per handle: [EditorViewModel] makes every call from one single-threaded dispatcher.
 */
internal object GrainhouseNative {
    init {
        System.loadLibrary("gal_editor")
    }

    /** Decodes the photo at [path] (JPEG, PNG, TIFF or a camera RAW); returns a handle for the calls below. */
    external fun open(path: String): Long

    external fun close(handle: Long)

    /** `{"w", "h", "raw", "asShotKelvin"}` */
    external fun info(handle: Long): String

    /** `[width, height, ARGB pixels...]` with the long side at most [long] pixels. */
    external fun render(handle: Long, settings: String, long: Int): IntArray

    /** The Auto light settings, as the `raw` object of Settings. */
    external fun autoLight(handle: Long, raw: String): String

    external fun applyPreset(current: String, preset: String): String

    /** Full-size render written to [outPath]; [format] 0 JPEG, 1 PNG, 2 TIFF. Keeps the original's EXIF. */
    external fun export(handle: Long, settings: String, outPath: String, format: Int, quality: Int)

    /** Lists for the pickers, built-in presets and the default settings (JSON). */
    external fun catalog(): String
}
