package com.gal.data.raw

import java.util.Locale

/**
 * Camera RAW formats Gal can preview. MediaStore already files these under images on Android 11+
 * (Sony ARW is `image/x-sony-arw`), but the platform decoders can't render most of them, so
 * [RawPreviewFetcher] pulls the JPEG preview the camera embedded in the file.
 */
object RawFormats {
    private val extensions = setOf(
        "arw", "srf", "sr2",            // Sony
        "dng",                          // Adobe / many phones
        "cr2", "crw",                   // Canon (CR3 is ISO-BMFF, not supported)
        "nef", "nrw",                   // Nikon
        "orf",                          // Olympus / OM System
        "raf",                          // Fujifilm
        "rw2", "rwl",                   // Panasonic / Leica
        "pef",                          // Pentax
        "srw",                          // Samsung
        "3fr", "fff",                   // Hasselblad
        "iiq",                          // Phase One
        "erf", "mef", "kdc", "dcr",     // Epson, Mamiya, Kodak
    )

    private val mimeTypes = setOf(
        "image/x-sony-arw", "image/x-sony-srf", "image/x-sony-sr2",
        "image/x-adobe-dng", "image/dng",
        "image/x-canon-cr2", "image/x-canon-crw",
        "image/x-nikon-nef", "image/x-nikon-nrw",
        "image/x-olympus-orf", "image/x-fuji-raf",
        "image/x-panasonic-rw2", "image/x-panasonic-rw",
        "image/x-pentax-pef", "image/x-samsung-srw",
        "image/x-hasselblad-3fr", "image/x-phaseone-iiq",
        "image/x-epson-erf", "image/x-mamiya-mef",
        "image/x-kodak-kdc", "image/x-kodak-dcr",
    )

    fun isRaw(displayName: String?, mimeType: String?): Boolean {
        if (mimeType != null && mimeType.lowercase(Locale.ROOT) in mimeTypes) return true
        val ext = displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: return false
        return ext in extensions
    }
}
