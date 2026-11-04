package com.gal.editor

import org.json.JSONArray
import org.json.JSONObject

/**
 * One state of an edit: Grainhouse `Settings` as JSON, read and changed by dotted paths such as
 * `raw.exposure` or `adjust.mixer.3.1` (array indices are numbers). Immutable: [with] returns a copy, so
 * any EditDoc can go on the undo stack. Fields the editor doesn't know about (from presets) are kept.
 */
class EditDoc private constructor(private val root: JSONObject) {

    val json: String = root.toString()

    fun float(path: String): Float = (lookup(path) as? Number)?.toFloat() ?: 0f
    fun int(path: String): Int = (lookup(path) as? Number)?.toInt() ?: 0
    fun bool(path: String): Boolean = lookup(path) as? Boolean ?: false
    fun string(path: String): String? = lookup(path) as? String
    fun isNull(path: String): Boolean = lookup(path).let { it == null || it == JSONObject.NULL }
    fun obj(path: String): JSONObject? = lookup(path) as? JSONObject

    /** A copy with [path] set to [value] (null clears it to JSON null). */
    fun with(path: String, value: Any?): EditDoc {
        val copy = JSONObject(json)
        val keys = path.split('.')
        var node: Any = copy
        for (k in keys.dropLast(1)) {
            node = when (node) {
                is JSONObject -> node.get(k)
                is JSONArray -> node.get(k.toInt())
                else -> error("bad path $path")
            }
        }
        val v = when (value) {
            null -> JSONObject.NULL
            is Float -> value.toDouble()
            else -> value
        }
        when (node) {
            is JSONObject -> node.put(keys.last(), v)
            is JSONArray -> node.put(keys.last().toInt(), v)
        }
        return EditDoc(copy)
    }

    private fun lookup(path: String): Any? {
        var node: Any? = root
        for (k in path.split('.')) {
            node = when (node) {
                is JSONObject -> node.opt(k)
                is JSONArray -> node.opt(k.toIntOrNull() ?: return null)
                else -> return null
            }
        }
        return node
    }

    override fun equals(other: Any?) = other is EditDoc && other.json == json
    override fun hashCode() = json.hashCode()

    companion object {
        fun parse(json: String) = EditDoc(JSONObject(json))
    }
}

/** What the editor's pickers offer, from the engine (see GrainhouseNative.catalog). */
class EditorCatalog private constructor(o: JSONObject) {
    class Film(val file: String, val name: String, val brand: String)
    class Choice(val index: Int, val name: String)
    class WhiteBalance(val name: String, val kelvin: Float)
    class Preset(val name: String, val settings: String)

    val films: List<Film> = o.getJSONArray("films").objects().map { Film(it.getString("file"), it.getString("name"), it.getString("brand")) }
    val brands: List<String> = o.getJSONArray("brands").strings()
    val creative: List<String> = o.getJSONArray("creative").strings()
    val cameras: List<String> = o.getJSONArray("cameras").strings()
    val frames: List<String> = o.getJSONArray("frames").strings()
    val diffusion: List<String> = o.getJSONArray("diffusion").strings()
    val grain: List<String> = o.getJSONArray("grain").strings()
    val localKinds: List<Choice> = o.getJSONArray("localKinds").objects().map { Choice(it.getInt("index"), it.getString("name")) }
    val whiteBalance: List<WhiteBalance> = o.getJSONArray("whiteBalance").objects().map { WhiteBalance(it.getString("name"), it.getDouble("kelvin").toFloat()) }
    val mixer: List<String> = o.getJSONArray("mixer").strings()
    val grades: List<String> = o.getJSONArray("grades").strings()
    val curveChannels: List<String> = o.getJSONArray("curveChannels").strings()
    val curveX: List<Float> = o.getJSONArray("curveX").let { a -> List(a.length()) { a.getDouble(it).toFloat() } }
    val presets: List<Preset> = o.getJSONArray("presets").objects().map { Preset(it.getString("name"), it.getJSONObject("settings").toString()) }
    val defaults: EditDoc = EditDoc.parse(o.getJSONObject("defaults").toString())

    companion object {
        /** Parsed once per process; the lists are compiled into the engine. */
        val instance: EditorCatalog by lazy { EditorCatalog(JSONObject(GrainhouseNative.catalog())) }

        private fun JSONArray.strings() = List(length()) { getString(it) }
        private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
    }
}
