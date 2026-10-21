package com.cusil.neodev

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class ProjectMeta(
    var owner: String = "",
    var repo: String = "",
    var branch: String = "main",
    var baseCommit: String = "",
    var manifest: MutableMap<String, String> = mutableMapOf()
) {
    val linked: Boolean get() = owner.isNotBlank() && repo.isNotBlank()
}

class ChangeSet(
    val added: List<String>,
    val modified: List<String>,
    val deleted: List<String>
) {
    val total: Int get() = added.size + modified.size + deleted.size
    val isEmpty: Boolean get() = total == 0
}

class ProjectStore(context: Context) {
    val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val metaDir = File(context.filesDir, "meta").apply { mkdirs() }
    private val chatDir = File(context.filesDir, "chats").apply { mkdirs() }

    fun names(): List<String> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .map { it.name }
            .sortedBy { it.lowercase() }

    fun dir(name: String): File = File(root, name)

    fun exists(name: String): Boolean = dir(name).exists()

    fun uniqueName(base: String): String {
        var n = base
        var i = 1
        while (exists(n)) {
            n = "$base-$i"
            i++
        }
        return n
    }

    fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-', '.')

    fun loadMeta(name: String): ProjectMeta {
        val f = File(metaDir, "$name.json")
        if (!f.exists()) return ProjectMeta()
        return try {
            val j = JSONObject(f.readText())
            val m = mutableMapOf<String, String>()
            val mj = j.optJSONObject("manifest")
            if (mj != null) {
                for (k in mj.keys()) m[k] = mj.getString(k)
            }
            ProjectMeta(
                j.optString("owner"), j.optString("repo"), j.optString("branch", "main"),
                j.optString("baseCommit"), m
            )
        } catch (e: Exception) {
            ProjectMeta()
        }
    }

    fun saveMeta(name: String, meta: ProjectMeta) {
        val mj = JSONObject()
        for ((k, v) in meta.manifest) mj.put(k, v)
        val j = JSONObject()
            .put("owner", meta.owner)
            .put("repo", meta.repo)
            .put("branch", meta.branch)
            .put("baseCommit", meta.baseCommit)
            .put("manifest", mj)
        File(metaDir, "$name.json").writeText(j.toString())
    }

    fun delete(name: String) {
        dir(name).deleteRecursively()
        File(metaDir, "$name.json").delete()
        File(chatDir, "$name.json").delete()
    }

    fun loadChat(name: String): List<ChatMsg> {
        val f = File(chatDir, "$name.json")
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<ChatMsg>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(ChatMsg(o.optBoolean("u"), o.optString("t")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveChat(name: String, msgs: List<ChatMsg>) {
        val arr = JSONArray()
        for (m in msgs.takeLast(60)) arr.put(JSONObject().put("u", m.fromUser).put("t", m.text))
        File(chatDir, "$name.json").writeText(arr.toString())
    }

    fun createFiles(name: String, files: Map<String, String>) {
        val base = dir(name)
        base.mkdirs()
        for ((rel, text) in files) {
            val f = File(base, rel)
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
    }

    /** All files of a project as relative path -> File, skipping .git folders. */
    fun scan(name: String): Map<String, File> = scanDir(dir(name))

    fun scanDir(base: File): Map<String, File> {
        val out = sortedMapOf<String, File>()
        base.walkTopDown()
            .onEnter { it.name != ".git" }
            .filter { it.isFile }
            .forEach { out[it.relativeTo(base).path.replace(File.separatorChar, '/')] = it }
        return out
    }

    /** Git blob SHA-1 of the given content. */
    fun sha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(("blob " + bytes.size + "\u0000").toByteArray())
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun changes(name: String): ChangeSet {
        val meta = loadMeta(name)
        val files = scan(name)
        val added = ArrayList<String>()
        val modified = ArrayList<String>()
        val deleted = ArrayList<String>()
        for ((rel, f) in files) {
            val old = meta.manifest[rel]
            if (old == null) added.add(rel)
            else if (old != sha(f.readBytes())) modified.add(rel)
        }
        for (rel in meta.manifest.keys) {
            if (!files.containsKey(rel)) deleted.add(rel)
        }
        return ChangeSet(added, modified, deleted.sorted())
    }

    /** Extracts a GitHub zipball, dropping the single top-level folder. */
    fun extractZip(zip: File, dest: File) {
        dest.mkdirs()
        val destPath = dest.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val rel = e.name.substringAfter('/', "")
                if (rel.isNotEmpty()) {
                    val out = File(dest, rel)
                    if (out.canonicalPath.startsWith(destPath + File.separator)) {
                        if (e.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zin.copyTo(it) }
                        }
                    }
                }
                e = zin.nextEntry
            }
        }
    }
}
