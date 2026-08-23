# ─── Gallery ProGuard / R8 rules ──────────────────────────────────────────────

# ── R8 full-mode: maximum shrinking and obfuscation ───────────────────────────
# allowaccessmodification lets R8 widen visibility to inline/merge more code.
# repackageclasses moves all surviving classes into a single flat package,
# making the class hierarchy unreadable to reverse-engineers.
-allowaccessmodification
-repackageclasses ''

# ── Strip all logging in release ──────────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# ── Security: tamper-detection must survive intact ────────────────────────────
# SignatureVerifier compares a hardcoded digest; renaming or inlining it would
# make the check trivially bypassable by a repackager.
-keep class com.gal.security.SignatureVerifier { *; }

# ── Hilt / Dagger ─────────────────────────────────────────────────────────────
# Hilt's AAR ships consumer rules for the bulk of its internals.
# These three are the generated aggregation / component classes that Hilt's
# component builder locates at runtime — they must not be renamed or removed.
-keep class hilt_aggregated_deps.** { *; }
-keep class dagger.hilt.internal.aggregatedroot.** { *; }
-keep class **_HiltComponents** { *; }

# ── Room ──────────────────────────────────────────────────────────────────────
# Room's annotation processor generates _Impl classes that reference entity
# field names; keep the annotated source types so the mapping survives.
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }

# ── Kotlin ────────────────────────────────────────────────────────────────────
# WhenMappings are synthetic classes for sealed/enum when-expressions.
# Metadata is read by Kotlin reflection and some libraries at runtime.
-keepclassmembers class **$WhenMappings { *; }
-keepclassmembers class kotlin.Metadata { *; }

# ── Media3 / ExoPlayer ────────────────────────────────────────────────────────
# Media3 ships consumer rules; these are a belt-and-braces guard for
# extractor and data-source implementations that are registered via
# ServiceLoader and may not be visible to R8's call-graph analysis.
-keep class androidx.media3.extractor.** implements androidx.media3.extractor.Extractor { *; }
-keep class androidx.media3.datasource.** implements androidx.media3.datasource.DataSource { *; }

# ── DataStore Preferences ─────────────────────────────────────────────────────
# DataStore's internal protobuf serialiser accesses generated field descriptors
# reflectively; preserve them so preferences survive across obfuscation.
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# ── Android Image Cropper (com.canhub.cropper) ───────────────────────────────
# CropImageView is constructed both in code and can be referenced from XML
# layout attributes; the library also uses reflection for its options.
-keep class com.canhub.cropper.** { *; }

# ── Android framework ─────────────────────────────────────────────────────────
# Parcelable CREATOR fields are looked up by the framework by exact name.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# JNI: keep native method signatures so the VM can resolve them.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Resource IDs accessed by name (e.g. via Resources.getIdentifier).
-keepclassmembers class **.R$* {
    public static <fields>;
}
