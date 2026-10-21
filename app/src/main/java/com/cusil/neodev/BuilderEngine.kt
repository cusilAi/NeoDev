package com.cusil.neodev

import org.json.JSONException
import org.json.JSONObject
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class FileChange(val path: String, val old: String?, val new: String?)

class Parsed(val reply: String, val files: List<Pair<String, String>>, val deleted: List<String>)

class BuilderResult(val changes: List<FileChange>, val skipped: List<String>)

object BuilderEngine {

    fun kindOf(store: ProjectStore, name: String): String {
        val files = store.scan(name).keys
        return when {
            files.any { it.endsWith("AndroidManifest.xml") } -> "android"
            files.contains("src/main/resources/plugin.yml") || files.any { it.endsWith("plugin.yml") } -> "paper"
            files.any { it.endsWith(".sk") } -> "skript"
            files.any { it.endsWith(".py") } -> "python"
            else -> "generic"
        }
    }

    fun greeting(kind: String, project: String, login: String): String {
        val what = when (kind) {
            "android" -> "Android app"
            "paper" -> "Paper plugin"
            "skript" -> "Skript"
            "python" -> "Python project"
            else -> "project"
        }
        val hi = if (login.isBlank()) "Hi!" else "Hi $login!"
        return "$hi I'm NeoDev, your builder. Tell me what you want and I'll write the complete $what for \"$project\". " +
            "After that, ask for any change you like: new features, a different look, bug fixes. " +
            "When it looks right, tap Build and I'll build it on GitHub for you."
    }

    fun suggestions(kind: String): List<String> = when (kind) {
        "android" -> listOf(
            "A weather app with city search and a 7-day forecast",
            "A to-do list with categories and a dark mode switch",
            "A unit converter for length, weight and temperature",
            "A habit tracker with daily streaks"
        )
        "paper" -> listOf(
            "A /sethome and /home plugin with a cooldown",
            "A custom join message plus /heal and /feed commands",
            "A simple warp system with /setwarp and /warp"
        )
        "skript" -> listOf(
            "A /spawn command with a 5 second cooldown",
            "A kit system with /kit starter",
            "A /coins system using variables"
        )
        "python" -> listOf(
            "A script that renames photos by date",
            "A command line to-do manager",
            "A Discord bot starter with slash commands"
        )
        else -> listOf("Set up a clean starter structure for this project")
    }

    fun system(kind: String, project: String): String {
        val common = """
You are NeoDev, an expert and friendly app builder inside a mobile code editor. You write complete, working projects and change them whenever the user asks.
The project is called "$project". Project type: $kind.

OUTPUT FORMAT. Reply with ONE JSON object and nothing else:
{"reply": "...", "files": [{"path": "...", "content": "..."}], "deleted": ["..."]}
- reply: a short, confident, friendly message in the user's language. Say what you built or changed, how it behaves, and offer one or two concrete ideas for the next change. Do not greet (the app already did) and do not paste code in the reply.
- files: the FULL new content of every file you create or change. Never send partial snippets and never write placeholders like "..." or "rest of the code". Leave unchanged files out. Use exact existing paths when editing a file.
- deleted: paths to remove (usually empty).
- If the user only asks a question or chats, answer in reply and send an empty files array.
- Only plain text files. Never include API keys or secrets.

QUALITY. Build what was asked fully, not a demo: polished UI, sensible defaults, clear loading, empty and error states, and clean readable code split into sensible files. When a request is vague, make good decisions yourself and mention them in reply. The project must build on the first try, so double-check imports, nullability, and every symbol you use. Prefer stable, widely used APIs.
"""
        val specific = when (kind) {
            "android" -> """
ANDROID RULES. The project already contains a working build scaffold; keep its versions: Android Gradle Plugin 8.5.2, Kotlin 2.0.20 with the Compose compiler plugin, Compose BOM 2024.09.03, compileSdk 34, minSdk 26. Do not change plugin versions or settings.gradle.kts. Edit app/build.gradle.kts only to add dependencies (and keep every existing line). Keep the existing namespace and package; put new Kotlin files in the same package folder under app/src/main/java.
- UI: Jetpack Compose with Material 3. Avoid experimental APIs, or add the needed @OptIn. No extended icon library unless you add androidx.compose.material:material-icons-extended (version comes from the BOM); emoji and text are fine as icons.
- Networking: add <uses-permission android:name="android.permission.INTERNET" /> to the manifest when needed. Use HttpURLConnection with org.json, or OkHttp (com.squareup.okhttp3:okhttp:4.12.0), run on Dispatchers.IO. Use free public APIs that need no key (for weather and geocoding use Open-Meteo). Use androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6 for ViewModel and state when helpful, and kotlinx-coroutines-android:1.8.1 for coroutines.
- Manifest: keep it valid XML, set a good android:label, keep MainActivity exported with the launcher intent filter.
- Persist small data with SharedPreferences when the app needs it.
"""
            "paper" -> """
PAPER RULES. Paper API 1.21.4 (io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT), Java 21, Gradle. Keep the existing build.gradle.kts structure. Declare every command, permission and the api-version in src/main/resources/plugin.yml. Keep the existing main class and package. Use the Adventure Component API for chat messages. Save data with the plugin's config or data folder. Put listeners and commands in separate classes.
"""
            "skript" -> """
SKRIPT RULES. Write clean, valid Skript syntax in .sk files under scripts/, with short comments, options for configurable values, and permission checks on admin commands. Keep to syntax that works on current Skript.
"""
            "python" -> """
PYTHON RULES. Plain Python 3 using the standard library when possible. Add a requirements.txt only if a third party package is needed. Keep a main.py entry point, add type hints and a short usage note in README.md.
"""
            else -> ""
        }
        return common + specific
    }

    private fun priority(path: String): Int {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when {
            ext in setOf("kt", "java", "sk", "py") -> 0
            path.endsWith("AndroidManifest.xml") || path.endsWith(".kts") || path.endsWith(".gradle") ||
                path.endsWith("plugin.yml") -> 1
            else -> 2
        }
    }

    /** Lists every file and includes the text of the most useful ones, so the AI sees the real project. */
    fun context(store: ProjectStore, name: String): String {
        val files = store.scan(name)
        val sb = StringBuilder()
        sb.append("CURRENT PROJECT FILES:\n")
        var n = 0
        for ((rel, f) in files) {
            if (n++ >= 300) { sb.append("- ... and more\n"); break }
            sb.append("- ").append(rel).append(" (").append(f.length()).append(" bytes)\n")
        }
        val textExt = setOf("kt", "java", "kts", "xml", "yml", "yaml", "sk", "py", "md", "properties", "json", "txt", "gradle", "toml")
        var budget = 70000
        sb.append("\nFILE CONTENTS:\n")
        for ((rel, f) in files.entries.sortedBy { priority(it.key) }) {
            if (rel.startsWith(".github/")) continue
            val ext = rel.substringAfterLast('.', "").lowercase()
            if (ext !in textExt || f.length() > 40000) continue
            val text = try { f.readText() } catch (e: Exception) { continue }
            if (text.length > budget) continue
            budget -= text.length
            sb.append("\n=== ").append(rel).append(" ===\n").append(text).append("\n")
        }
        return sb.toString()
    }

    fun parse(raw: String): Parsed {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim().removeSuffix("```").trim()
        }
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) throw AppException("NeoDev's answer was cut off. Try again, or ask for a smaller change.")
        val j = try {
            JSONObject(t.substring(a, b + 1))
        } catch (e: JSONException) {
            throw AppException("NeoDev's answer was cut off or malformed. Try again, or ask for a smaller change.")
        }
        val files = ArrayList<Pair<String, String>>()
        val fa = j.optJSONArray("files")
        if (fa != null) {
            for (i in 0 until fa.length()) {
                val o = fa.optJSONObject(i) ?: continue
                val p = o.optString("path", "")
                if (p.isNotBlank()) files.add(p to o.optString("content", ""))
            }
        }
        val deleted = ArrayList<String>()
        val da = j.optJSONArray("deleted")
        if (da != null) {
            for (i in 0 until da.length()) deleted.add(da.optString(i, ""))
        }
        return Parsed(j.optString("reply", ""), files, deleted)
    }

    private fun safePath(p: String): String? {
        val s = p.trim().replace('\\', '/').removePrefix("./")
        if (s.isEmpty() || s.startsWith("/") || s.length > 200) return null
        val parts = s.split('/')
        if (parts.any { it.isEmpty() || it == ".." || it == "." }) return null
        if (parts[0] == ".git" || parts[0] == ".github") return null
        return s
    }

    private fun xmlOk(text: String): Boolean = try {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(text)))
        true
    } catch (e: Exception) {
        false
    }

    /** Writes the AI's files into the project. Nothing is written if a generated XML file is broken. */
    fun apply(store: ProjectStore, name: String, p: Parsed): BuilderResult {
        val base = store.dir(name)
        val skipped = ArrayList<String>()
        val writes = LinkedHashMap<String, String>()
        for ((path, content) in p.files.take(40)) {
            val safe = safePath(path)
            if (safe == null || content.length > 300_000) {
                skipped.add(path)
                continue
            }
            if (safe.endsWith(".xml") && !xmlOk(content)) {
                throw AppException("NeoDev wrote $safe with invalid XML, so nothing was changed. Ask again.")
            }
            writes[safe] = content
        }
        val deletes = p.deleted.mapNotNull { safePath(it) }.filter { !writes.containsKey(it) }

        val changes = ArrayList<FileChange>()
        for ((rel, content) in writes) {
            val f = File(base, rel)
            val old = if (f.isFile) {
                try { f.readText() } catch (e: Exception) { null }
            } else null
            if (old == content) continue
            f.parentFile?.mkdirs()
            f.writeText(content)
            changes.add(FileChange(rel, old, content))
        }
        for (rel in deletes) {
            val f = File(base, rel)
            if (f.isFile) {
                val old = try { f.readText() } catch (e: Exception) { "" }
                f.delete()
                changes.add(FileChange(rel, old, null))
            }
        }
        return BuilderResult(changes, skipped)
    }

    fun revert(store: ProjectStore, name: String, changes: List<FileChange>) {
        val base = store.dir(name)
        for (c in changes) {
            val f = File(base, c.path)
            if (c.old == null) {
                f.delete()
            } else {
                f.parentFile?.mkdirs()
                f.writeText(c.old)
            }
        }
    }
}
