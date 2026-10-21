package com.cusil.neodev

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class AppException(message: String, val code: Int = 0) : Exception(message)

data class Repo(
    val owner: String,
    val name: String,
    val branch: String,
    val isPrivate: Boolean,
    val description: String
)

data class TreeEntry(val path: String, val mode: String, val sha: String?)

data class GhWorkflow(val id: Long, val name: String, val path: String)

data class GhRun(
    val id: Long,
    val number: Int,
    val title: String,
    val status: String,
    val conclusion: String,
    val branch: String,
    val created: String,
    val url: String
) {
    val running: Boolean get() = status != "completed"
    val label: String get() = if (running) status.replace('_', ' ') else conclusion.ifBlank { "done" }
}

data class GhStep(val number: Int, val name: String, val status: String, val conclusion: String)

data class GhJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String,
    val steps: List<GhStep>
)

data class GhArtifact(val id: Long, val name: String, val size: Long, val expired: Boolean)

private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

class GitHubApi(private val token: () -> String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val api = "https://api.github.com"

    private fun builder(url: String): Request.Builder {
        val b = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "NeoDev-Android")
        val t = token()
        if (t.isNotBlank()) b.header("Authorization", "Bearer $t")
        return b
    }

    private fun friendly(code: Int, body: String): String {
        val msg = try { JSONObject(body).optString("message", "") } catch (e: Exception) { "" }
        return when (code) {
            401 -> "GitHub rejected the token (401). Check or replace it in Settings."
            403 -> "GitHub refused the request (403): " + msg.ifBlank { "missing permission or rate limit" } +
                ". The token needs the repo and workflow scopes."
            404 -> "Not found (404). Check that the token has the repo and workflow scopes and that the item exists. $msg"
            409 -> "Conflict (409): " + msg.ifBlank { "the repository may be empty" }
            422 -> "GitHub says (422): $msg"
            else -> "GitHub error $code: $msg"
        }
    }

    private fun send(b: Request.Builder): String {
        return try {
            http.newCall(b.build()).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) throw AppException(friendly(r.code, text), r.code)
                text
            }
        } catch (e: AppException) {
            throw e
        } catch (e: IOException) {
            throw AppException("Network error: " + (e.message ?: "no connection"))
        }
    }

    private fun get(path: String): String = send(builder(api + path))

    private fun post(path: String, json: JSONObject): String =
        send(builder(api + path).post(json.toString().toRequestBody(jsonType)))

    private fun patch(path: String, json: JSONObject): String =
        send(builder(api + path).patch(json.toString().toRequestBody(jsonType)))

    // ---------- account and repositories ----------

    fun login(): String = JSONObject(get("/user")).str("login")

    private fun repoOf(o: JSONObject): Repo = Repo(
        owner = o.getJSONObject("owner").str("login"),
        name = o.str("name"),
        branch = o.str("default_branch").ifBlank { "main" },
        isPrivate = o.optBoolean("private"),
        description = o.str("description")
    )

    fun listRepos(): List<Repo> {
        val out = ArrayList<Repo>()
        for (page in 1..5) {
            val arr = JSONArray(get("/user/repos?per_page=100&page=$page&sort=updated"))
            for (i in 0 until arr.length()) out.add(repoOf(arr.getJSONObject(i)))
            if (arr.length() < 100) break
        }
        return out
    }

    fun createRepo(name: String, isPrivate: Boolean, description: String): Repo {
        val body = JSONObject()
            .put("name", name)
            .put("private", isPrivate)
            .put("auto_init", true)
            .put("description", description)
        return repoOf(JSONObject(post("/user/repos", body)))
    }

    // ---------- git data ----------

    fun headSha(owner: String, repo: String, branch: String): String =
        JSONObject(get("/repos/$owner/$repo/git/ref/heads/$branch")).getJSONObject("object").getString("sha")

    fun commitTree(owner: String, repo: String, commitSha: String): String =
        JSONObject(get("/repos/$owner/$repo/git/commits/$commitSha")).getJSONObject("tree").getString("sha")

    fun createBlob(owner: String, repo: String, bytes: ByteArray): String {
        val body = JSONObject()
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("encoding", "base64")
        return JSONObject(post("/repos/$owner/$repo/git/blobs", body)).getString("sha")
    }

    fun createTree(owner: String, repo: String, baseTree: String, entries: List<TreeEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject().put("path", e.path).put("mode", e.mode).put("type", "blob")
            if (e.sha == null) o.put("sha", JSONObject.NULL) else o.put("sha", e.sha)
            arr.put(o)
        }
        val body = JSONObject().put("base_tree", baseTree).put("tree", arr)
        return JSONObject(post("/repos/$owner/$repo/git/trees", body)).getString("sha")
    }

    fun createCommit(
        owner: String, repo: String, message: String, tree: String, parent: String,
        authorName: String, authorEmail: String
    ): String {
        val body = JSONObject()
            .put("message", message)
            .put("tree", tree)
            .put("parents", JSONArray().put(parent))
        if (authorName.isNotBlank() && authorEmail.isNotBlank()) {
            body.put("author", JSONObject().put("name", authorName).put("email", authorEmail))
        }
        return JSONObject(post("/repos/$owner/$repo/git/commits", body)).getString("sha")
    }

    fun updateRef(owner: String, repo: String, branch: String, sha: String) {
        patch("/repos/$owner/$repo/git/refs/heads/$branch", JSONObject().put("sha", sha))
    }

    // ---------- downloads ----------

    private fun download(url: String, dest: File) {
        try {
            http.newCall(builder(url).build()).execute().use { r ->
                if (!r.isSuccessful) {
                    throw AppException(friendly(r.code, r.body?.string() ?: ""), r.code)
                }
                dest.parentFile?.mkdirs()
                val body = r.body ?: throw AppException("Empty download")
                body.byteStream().use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
            }
        } catch (e: AppException) {
            throw e
        } catch (e: IOException) {
            throw AppException("Network error: " + (e.message ?: "download failed"))
        }
    }

    fun downloadRepoZip(owner: String, repo: String, ref: String, dest: File) =
        download("$api/repos/$owner/$repo/zipball/$ref", dest)

    fun downloadArtifact(owner: String, repo: String, artifactId: Long, dest: File) =
        download("$api/repos/$owner/$repo/actions/artifacts/$artifactId/zip", dest)

    // ---------- actions ----------

    fun listWorkflows(owner: String, repo: String): List<GhWorkflow> {
        val arr = JSONObject(get("/repos/$owner/$repo/actions/workflows")).getJSONArray("workflows")
        val out = ArrayList<GhWorkflow>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.str("state") == "active") out.add(GhWorkflow(o.getLong("id"), o.str("name"), o.str("path")))
        }
        return out
    }

    fun dispatch(owner: String, repo: String, workflowId: Long, ref: String) {
        post("/repos/$owner/$repo/actions/workflows/$workflowId/dispatches", JSONObject().put("ref", ref))
    }

    private fun runOf(o: JSONObject) = GhRun(
        id = o.getLong("id"),
        number = o.optInt("run_number"),
        title = o.str("display_title").ifBlank { o.str("name") },
        status = o.str("status"),
        conclusion = o.str("conclusion"),
        branch = o.str("head_branch"),
        created = o.str("created_at"),
        url = o.str("html_url")
    )

    fun listRuns(owner: String, repo: String): List<GhRun> {
        val arr = JSONObject(get("/repos/$owner/$repo/actions/runs?per_page=15")).getJSONArray("workflow_runs")
        val out = ArrayList<GhRun>()
        for (i in 0 until arr.length()) out.add(runOf(arr.getJSONObject(i)))
        return out
    }

    fun getRun(owner: String, repo: String, runId: Long): GhRun =
        runOf(JSONObject(get("/repos/$owner/$repo/actions/runs/$runId")))

    fun listJobs(owner: String, repo: String, runId: Long): List<GhJob> {
        val arr = JSONObject(get("/repos/$owner/$repo/actions/runs/$runId/jobs")).getJSONArray("jobs")
        val out = ArrayList<GhJob>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val steps = ArrayList<GhStep>()
            val sa = o.optJSONArray("steps")
            if (sa != null) {
                for (j in 0 until sa.length()) {
                    val s = sa.getJSONObject(j)
                    steps.add(GhStep(s.optInt("number"), s.str("name"), s.str("status"), s.str("conclusion")))
                }
            }
            out.add(GhJob(o.getLong("id"), o.str("name"), o.str("status"), o.str("conclusion"), steps))
        }
        return out
    }

    fun jobLog(owner: String, repo: String, jobId: Long): String =
        get("/repos/$owner/$repo/actions/jobs/$jobId/logs")

    fun listArtifacts(owner: String, repo: String, runId: Long): List<GhArtifact> {
        val arr = JSONObject(get("/repos/$owner/$repo/actions/runs/$runId/artifacts")).getJSONArray("artifacts")
        val out = ArrayList<GhArtifact>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(GhArtifact(o.getLong("id"), o.str("name"), o.optLong("size_in_bytes"), o.optBoolean("expired")))
        }
        return out
    }

    fun cancelRun(owner: String, repo: String, runId: Long) {
        post("/repos/$owner/$repo/actions/runs/$runId/cancel", JSONObject())
    }
}
