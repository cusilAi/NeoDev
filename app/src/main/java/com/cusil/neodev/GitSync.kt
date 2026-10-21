package com.cusil.neodev

import java.io.File

class PullResult(val upToDate: Boolean, val updated: Int, val deleted: Int, val conflicts: List<String>)

class GitSync(
    private val api: GitHubApi,
    private val store: ProjectStore,
    private val cacheDir: File
) {

    fun clone(repo: Repo, name: String, progress: (String) -> Unit) {
        val dir = store.dir(name)
        if (dir.exists()) throw AppException("A project named $name already exists.")
        progress("Reading ${repo.name}…")
        val head = api.headSha(repo.owner, repo.name, repo.branch)
        val zip = File(cacheDir, "clone-${System.nanoTime()}.zip")
        try {
            progress("Downloading ${repo.name}…")
            api.downloadRepoZip(repo.owner, repo.name, repo.branch, zip)
            progress("Unpacking…")
            store.extractZip(zip, dir)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        } finally {
            zip.delete()
        }
        val manifest = mutableMapOf<String, String>()
        for ((rel, f) in store.scan(name)) manifest[rel] = store.sha(f.readBytes())
        store.saveMeta(name, ProjectMeta(repo.owner, repo.name, repo.branch, head, manifest))
    }

    fun pull(name: String, progress: (String) -> Unit): PullResult {
        val meta = store.loadMeta(name)
        if (!meta.linked) throw AppException("This project is not linked to a GitHub repository.")
        progress("Checking remote…")
        val head = api.headSha(meta.owner, meta.repo, meta.branch)
        if (head == meta.baseCommit) return PullResult(true, 0, 0, emptyList())

        val zip = File(cacheDir, "pull-${System.nanoTime()}.zip")
        val tmp = File(cacheDir, "pull-${System.nanoTime()}")
        try {
            progress("Downloading latest…")
            api.downloadRepoZip(meta.owner, meta.repo, meta.branch, zip)
            store.extractZip(zip, tmp)
            val remote = store.scanDir(tmp)
            val local = store.scan(name)
            val base = store.dir(name)
            val newManifest = mutableMapOf<String, String>()
            var updated = 0
            var deleted = 0
            val conflicts = ArrayList<String>()

            progress("Merging…")
            for ((rel, rf) in remote) {
                val rBytes = rf.readBytes()
                val rSha = store.sha(rBytes)
                newManifest[rel] = rSha
                val oldSha = meta.manifest[rel]
                val lf = local[rel]
                if (lf == null) {
                    if (oldSha == null || oldSha != rSha) {
                        writeBytes(File(base, rel), rBytes)
                        updated++
                    }
                } else {
                    val lSha = store.sha(lf.readBytes())
                    if (lSha == rSha) {
                        // already identical
                    } else if (lSha == oldSha) {
                        writeBytes(lf, rBytes)
                        updated++
                    } else if (oldSha == rSha) {
                        // only changed locally, keep it
                    } else {
                        conflicts.add(rel)
                    }
                }
            }
            for ((rel, lf) in local) {
                if (!remote.containsKey(rel)) {
                    val oldSha = meta.manifest[rel]
                    if (oldSha != null && oldSha == store.sha(lf.readBytes())) {
                        lf.delete()
                        deleted++
                    }
                }
            }
            meta.manifest = newManifest
            meta.baseCommit = head
            store.saveMeta(name, meta)
            return PullResult(false, updated, deleted, conflicts)
        } finally {
            zip.delete()
            tmp.deleteRecursively()
        }
    }

    private fun writeBytes(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    private fun modeFor(rel: String): String {
        val n = rel.substringAfterLast('/')
        return if (n == "gradlew" || n.endsWith(".sh")) "100755" else "100644"
    }

    /** Commits and pushes all changes (or only the given paths) as one commit. Returns the commit SHA. */
    fun push(
        name: String,
        message: String,
        authorName: String,
        authorEmail: String,
        only: Set<String>? = null,
        progress: (String) -> Unit
    ): String {
        val meta = store.loadMeta(name)
        if (!meta.linked) throw AppException("Link this project to a GitHub repository first.")
        val all = store.changes(name)
        val added = all.added.filter { only == null || it in only }
        val modified = all.modified.filter { only == null || it in only }
        val deleted = all.deleted.filter { only == null || it in only }
        if (added.isEmpty() && modified.isEmpty() && deleted.isEmpty()) {
            throw AppException("Nothing to commit.")
        }

        progress("Checking remote…")
        val head = api.headSha(meta.owner, meta.repo, meta.branch)
        if (meta.baseCommit.isNotEmpty() && head != meta.baseCommit) {
            throw AppException("The remote branch has new commits. Pull first, then push again.")
        }
        val baseTree = api.commitTree(meta.owner, meta.repo, head)
        val files = store.scan(name)
        val entries = ArrayList<TreeEntry>()
        val shas = mutableMapOf<String, String>()
        val toUpload = added + modified
        for ((i, rel) in toUpload.withIndex()) {
            progress("Uploading ${i + 1}/${toUpload.size}: ${rel.substringAfterLast('/')}")
            val bytes = files.getValue(rel).readBytes()
            val blob = api.createBlob(meta.owner, meta.repo, bytes)
            shas[rel] = store.sha(bytes)
            entries.add(TreeEntry(rel, modeFor(rel), blob))
        }
        for (rel in deleted) entries.add(TreeEntry(rel, "100644", null))

        progress("Creating commit…")
        val tree = api.createTree(meta.owner, meta.repo, baseTree, entries)
        val commit = api.createCommit(
            meta.owner, meta.repo, message.ifBlank { "Update from NeoDev" },
            tree, head, authorName, authorEmail
        )
        api.updateRef(meta.owner, meta.repo, meta.branch, commit)

        for ((rel, sha) in shas) meta.manifest[rel] = sha
        for (rel in deleted) meta.manifest.remove(rel)
        meta.baseCommit = commit
        store.saveMeta(name, meta)
        return commit
    }

    /** Creates a new GitHub repository and links the local project to it. */
    fun createAndLink(name: String, repoName: String, isPrivate: Boolean, progress: (String) -> Unit) {
        progress("Creating repository…")
        val repo = api.createRepo(repoName, isPrivate, "Created with NeoDev")
        var head = ""
        for (attempt in 1..4) {
            try {
                head = api.headSha(repo.owner, repo.name, repo.branch)
                break
            } catch (e: AppException) {
                if (attempt == 4) throw e
                Thread.sleep(1500)
            }
        }
        store.saveMeta(name, ProjectMeta(repo.owner, repo.name, repo.branch, head, mutableMapOf()))
    }
}
