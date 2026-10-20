package `in`.hrishikesh7665.killora

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns the Quick List into a small JSON file and back, so it can be backed up, moved to another
 * phone or shared.
 *
 * Pure data code: it never touches [QuickList] itself. The caller decides how an imported list is
 * merged (see MainActivity), which keeps the rule in [QuickList] intact: adding only ever merges,
 * and nothing already saved is replaced.
 *
 * File shape (version 1):
 * ```
 * { "format": "killora-quick-list", "version": 1, "exportedAt": "...",
 *   "apps": [ { "package": "com.example.app", "label": "Example" }, ... ] }
 * ```
 * [parse] is deliberately forgiving: it also accepts a bare JSON array of package names and a
 * plain text file with one package name per line, so a hand-written list works too.
 */
object QuickListBackup {

    const val MIME_TYPE = "application/json"

    /** File providers disagree about what a `.json` file is, so the picker accepts all of these. */
    val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

    private const val FORMAT_ID = "killora-quick-list"
    private const val FORMAT_VERSION = 1

    /** A real list is a few KB. This only stops an accidentally huge file from filling memory. */
    private const val MAX_FILE_BYTES = 1_000_000
    private const val MAX_ENTRIES = 5_000
    private const val CHUNK_BYTES = 8 * 1024

    /**
     * Android package-name rules: two or more dot-separated segments, each starting with a letter
     * and made of letters, digits and underscores. This also guarantees a name can never contain
     * the comma [Prefs] uses to store the list.
     */
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+\$")

    /** One saved app. [label] is only a courtesy for people reading the file; import ignores it. */
    data class Entry(val packageName: String, val label: String?)

    sealed class ParseResult {
        /** [packages] is de-duplicated, in file order; [invalid] counts entries that were skipped. */
        class Success(val packages: List<String>, val invalid: Int) : ParseResult()

        /** The text was not JSON that holds a list of apps. */
        object NotAQuickList : ParseResult()
    }

    fun suggestedFileName(nowMillis: Long = System.currentTimeMillis()): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(nowMillis))
        return "killora-quick-list-$day.json"
    }

    fun toJson(entries: List<Entry>, nowMillis: Long = System.currentTimeMillis()): String {
        val apps = JSONArray()
        for (entry in entries) {
            val item = JSONObject().put("package", entry.packageName)
            if (!entry.label.isNullOrBlank()) item.put("label", entry.label)
            apps.put(item)
        }
        val exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(nowMillis))
        return JSONObject()
            .put("format", FORMAT_ID)
            .put("version", FORMAT_VERSION)
            .put("exportedAt", exportedAt)
            .put("apps", apps)
            .toString(2)
    }

    /**
     * @param ownPackage Killora's own package name. It is dropped silently, because the app list
     *   never offers Killora itself and closing it from its own Quick List makes no sense.
     */
    fun parse(text: String, ownPackage: String): ParseResult {
        val body = text.removePrefix("\uFEFF").trim()
        val names: List<String> = try {
            when {
                body.startsWith("{") -> {
                    val apps = JSONObject(body).optJSONArray("apps") ?: return ParseResult.NotAQuickList
                    namesFrom(apps)
                }
                body.startsWith("[") -> namesFrom(JSONArray(body))
                else -> body.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }
        } catch (_: JSONException) {
            return ParseResult.NotAQuickList
        }

        val valid = LinkedHashSet<String>()
        var invalid = 0
        for (name in names.take(MAX_ENTRIES)) {
            when {
                name == ownPackage -> Unit
                PACKAGE_NAME.matches(name) -> valid.add(name)
                else -> invalid++
            }
        }
        return ParseResult.Success(valid.toList(), invalid)
    }

    /** Package names from a JSON array of strings or of `{ "package": ... }` objects. Anything
     *  else becomes an empty string, which [parse] then counts as an invalid entry. */
    private fun namesFrom(array: JSONArray): List<String> =
        (0 until array.length()).map { index ->
            when (val item = array.opt(index)) {
                is String -> item.trim()
                is JSONObject -> item.optString("package", "").trim()
                else -> ""
            }
        }

    /** Reads the whole file as UTF-8, or returns null if it cannot be read or is too large. */
    fun read(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(CHUNK_BYTES)
                var total = 0
                var tooLarge = false
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_FILE_BYTES) {
                        tooLarge = true
                        break
                    }
                    out.write(chunk, 0, read)
                }
                if (tooLarge) null else String(out.toByteArray(), Charsets.UTF_8)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** Writes [text] to [uri], replacing whatever was there. Returns whether it worked. */
    fun write(resolver: ContentResolver, uri: Uri, text: String): Boolean {
        return try {
            resolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                true
            } ?: false
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
