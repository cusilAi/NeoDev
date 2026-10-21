package com.cusil.neodev

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class Screen { Home, Settings, Clone, NewProject, Workspace, Git, Build, Builder }

class EditorTab(val path: String, val file: File, initial: String) {
    var value by mutableStateOf(TextFieldValue(initial))
    var saved by mutableStateOf(initial)
    val dirty: Boolean get() = value.text != saved
    val undo = ArrayList<String>()
    val redo = ArrayList<String>()
    var lastSnap = 0L
    val name: String get() = path.substringAfterLast('/')
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

data class TreeRow(val rel: String, val name: String, val depth: Int, val isDir: Boolean, val expanded: Boolean)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val ctx: Application = app
    val prefs = Prefs(app)
    val store = ProjectStore(app)
    val api = GitHubApi { prefs.githubToken }
    private val sync = GitSync(api, store, app.cacheDir)
    private val gemini = GeminiClient({ prefs.effectiveGeminiKey() }, { prefs.model })

    var screen by mutableStateOf(Screen.Home)
    var projects by mutableStateOf(store.names())
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var fontSize by mutableIntStateOf(prefs.fontSize)

    fun say(m: String) { message = m }

    /** Runs blocking work off the main thread with a progress overlay and error reporting. */
    fun work(label: String, block: () -> Unit) {
        if (busy != null) return
        busy = label
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: AppException) {
                say(e.message ?: "Something went wrong.")
            } catch (e: Exception) {
                say("Error: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                busy = null
            }
        }
    }

    fun go(s: Screen) { screen = s }

    fun back() {
        when (screen) {
            Screen.Workspace -> { saveAll(); closeProject(); screen = Screen.Home }
            Screen.Git -> { screen = Screen.Workspace; refreshTree() }
            Screen.Build -> { stopPolling(); screen = Screen.Workspace; refreshTree() }
            Screen.Builder -> { screen = Screen.Workspace; refreshTree(); reloadTabs() }
            else -> screen = Screen.Home
        }
    }

    // ------------------------------------------------------------------ settings

    fun testToken() = work("Checking token…") {
        val login = api.login()
        prefs.githubLogin = login
        say("Signed in as $login")
    }

    fun authorName(): String = prefs.authorName.ifBlank { prefs.githubLogin }

    fun authorEmail(): String = prefs.authorEmail.ifBlank {
        if (prefs.githubLogin.isBlank()) "" else prefs.githubLogin + "@users.noreply.github.com"
    }

    // ------------------------------------------------------------------ projects

    var repos by mutableStateOf(listOf<Repo>())

    fun refreshProjects() { projects = store.names() }

    fun loadRepos() = work("Loading your repositories…") {
        repos = api.listRepos()
    }

    fun cloneRepo(r: Repo) = work("Cloning ${r.name}…") {
        val name = store.uniqueName(r.name)
        sync.clone(r, name) { busy = it }
        refreshProjects()
        say("Cloned ${r.name}")
        screen = Screen.Home
    }

    fun createProject(rawName: String, kind: String, withAi: Boolean = false) {
        val clean = store.sanitize(rawName)
        if (clean.isBlank()) { say("Enter a project name (letters, numbers, - _ .)."); return }
        work("Creating project…") {
            val name = store.uniqueName(clean)
            store.createFiles(name, Templates.files(kind, name))
            refreshProjects()
            openProject(name)
            if (withAi) startBuilder()
        }
    }

    fun deleteProject(name: String) {
        store.delete(name)
        refreshProjects()
    }

    // ------------------------------------------------------------------ workspace

    var project by mutableStateOf("")
    var meta by mutableStateOf(ProjectMeta())
    val tabs = mutableStateListOf<EditorTab>()
    var activeTab by mutableIntStateOf(-1)
    var rows by mutableStateOf(listOf<TreeRow>())
    private val expandedDirs = HashSet<String>()

    var findOpen by mutableStateOf(false)
    var findText by mutableStateOf("")
    var replaceText by mutableStateOf("")
    var scrollTo by mutableStateOf<Int?>(null)

    fun openProject(name: String) {
        project = name
        tabs.clear()
        activeTab = -1
        expandedDirs.clear()
        aiMessages.clear()
        aiOpen = false
        findOpen = false
        meta = store.loadMeta(name)
        refreshTree()
        screen = Screen.Workspace
    }

    private fun closeProject() {
        tabs.clear()
        activeTab = -1
        project = ""
        refreshProjects()
    }

    val currentTab: EditorTab? get() = tabs.getOrNull(activeTab)

    fun refreshTree() {
        if (project.isEmpty()) return
        val base = store.dir(project)
        val out = ArrayList<TreeRow>()
        fun walk(dir: File, depth: Int) {
            val kids = (dir.listFiles() ?: emptyArray())
                .filter { it.name != ".git" }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            for (k in kids) {
                val rel = k.relativeTo(base).path.replace(File.separatorChar, '/')
                val isOpen = k.isDirectory && expandedDirs.contains(rel)
                out.add(TreeRow(rel, k.name, depth, k.isDirectory, isOpen))
                if (isOpen) walk(k, depth + 1)
            }
        }
        walk(base, 0)
        rows = out
    }

    fun toggleDir(rel: String) {
        if (!expandedDirs.remove(rel)) expandedDirs.add(rel)
        refreshTree()
    }

    fun openFile(rel: String) {
        val idx = tabs.indexOfFirst { it.path == rel }
        if (idx >= 0) { activeTab = idx; return }
        val f = File(store.dir(project), rel)
        if (!f.isFile) return
        if (f.length() > 1_500_000) { say("This file is too large to edit here."); return }
        val bytes = f.readBytes()
        if (bytes.take(8000).any { it.toInt() == 0 }) { say("This is a binary file and can't be edited as text."); return }
        tabs.add(EditorTab(rel, f, String(bytes, Charsets.UTF_8)))
        activeTab = tabs.lastIndex
    }

    fun closeTab(i: Int, save: Boolean) {
        val t = tabs.getOrNull(i) ?: return
        if (save && t.dirty) saveTab(t)
        tabs.removeAt(i)
        activeTab = when {
            tabs.isEmpty() -> -1
            activeTab > tabs.lastIndex -> tabs.lastIndex
            activeTab > i -> activeTab - 1
            else -> activeTab
        }
    }

    fun saveTab(t: EditorTab) {
        try {
            t.file.writeText(t.value.text)
            t.saved = t.value.text
        } catch (e: Exception) {
            say("Could not save ${t.name}: ${e.message}")
        }
    }

    fun saveAll() {
        for (t in tabs) if (t.dirty) saveTab(t)
    }

    fun newEntry(parentRel: String, name: String, isDir: Boolean) {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains('\\')) { say("Enter a valid name."); return }
        val f = File(File(store.dir(project), parentRel), clean)
        if (f.exists()) { say("$clean already exists."); return }
        try {
            if (isDir) f.mkdirs() else { f.parentFile?.mkdirs(); f.writeText("") }
        } catch (e: Exception) {
            say("Could not create it: ${e.message}"); return
        }
        if (parentRel.isNotEmpty()) expandedDirs.add(parentRel)
        refreshTree()
        if (!isDir) openFile((if (parentRel.isEmpty()) "" else "$parentRel/") + clean)
    }

    fun renameEntry(rel: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains('\\')) { say("Enter a valid name."); return }
        saveAll()
        val src = File(store.dir(project), rel)
        val dst = File(src.parentFile, clean)
        if (dst.exists()) { say("$clean already exists."); return }
        if (!src.renameTo(dst)) { say("Could not rename it."); return }
        closeTabsUnder(rel)
        expandedDirs.remove(rel)
        refreshTree()
    }

    fun deleteEntry(rel: String) {
        closeTabsUnder(rel)
        File(store.dir(project), rel).deleteRecursively()
        expandedDirs.remove(rel)
        refreshTree()
    }

    private fun closeTabsUnder(rel: String) {
        var i = tabs.lastIndex
        while (i >= 0) {
            val p = tabs[i].path
            if (p == rel || p.startsWith("$rel/")) closeTab(i, false)
            i--
        }
    }

    private fun reloadTabs() {
        var i = tabs.lastIndex
        while (i >= 0) {
            val t = tabs[i]
            if (!t.file.exists()) {
                closeTab(i, false)
            } else if (!t.dirty) {
                val text = t.file.readText()
                t.value = TextFieldValue(text)
                t.saved = text
            }
            i--
        }
    }

    // ------------------------------------------------------------------ editing

    fun onEdit(tab: EditorTab, nv: TextFieldValue) {
        val old = tab.value
        var v = nv
        if (nv.text.length == old.text.length + 1 && nv.selection.collapsed && nv.selection.start > 0 &&
            nv.text[nv.selection.start - 1] == '\n'
        ) {
            val pos = nv.selection.start
            val lineStart = nv.text.lastIndexOf('\n', pos - 2) + 1
            val prev = nv.text.substring(lineStart, pos - 1)
            var indent = prev.takeWhile { it == ' ' || it == '\t' }
            val trimmed = prev.trimEnd()
            if (trimmed.endsWith("{") || trimmed.endsWith(":") || trimmed.endsWith("(")) indent += "    "
            if (indent.isNotEmpty()) {
                v = TextFieldValue(
                    nv.text.substring(0, pos) + indent + nv.text.substring(pos),
                    TextRange(pos + indent.length)
                )
            }
        }
        if (v.text != old.text) {
            val now = System.currentTimeMillis()
            if (now - tab.lastSnap > 700 || tab.undo.isEmpty()) {
                tab.undo.add(old.text)
                if (tab.undo.size > 80) tab.undo.removeAt(0)
                tab.lastSnap = now
            }
            tab.redo.clear()
        }
        tab.value = v
    }

    fun insertText(tab: EditorTab, s: String, back: Int = 0) {
        val sel = tab.value.selection
        val t = tab.value.text.replaceRange(sel.min, sel.max, s)
        onEdit(tab, TextFieldValue(t, TextRange(sel.min + s.length - back)))
    }

    fun undo(tab: EditorTab) {
        if (tab.undo.isEmpty()) return
        tab.redo.add(tab.value.text)
        val text = tab.undo.removeAt(tab.undo.lastIndex)
        tab.value = TextFieldValue(text, TextRange(minOf(tab.value.selection.end, text.length)))
        tab.lastSnap = 0L
    }

    fun redo(tab: EditorTab) {
        if (tab.redo.isEmpty()) return
        tab.undo.add(tab.value.text)
        val text = tab.redo.removeAt(tab.redo.lastIndex)
        tab.value = TextFieldValue(text, TextRange(minOf(tab.value.selection.end, text.length)))
        tab.lastSnap = 0L
    }

    fun replaceWholeFile(tab: EditorTab, text: String) {
        tab.undo.add(tab.value.text)
        tab.redo.clear()
        tab.value = TextFieldValue(text, TextRange(0))
        tab.lastSnap = 0L
    }

    fun findNext(tab: EditorTab): Boolean {
        val q = findText
        if (q.isEmpty()) return false
        val text = tab.value.text
        var from = tab.value.selection.max
        var idx = text.indexOf(q, from, ignoreCase = true)
        if (idx < 0) { from = 0; idx = text.indexOf(q, 0, ignoreCase = true) }
        if (idx < 0) { say("No match for \"$q\""); return false }
        tab.value = tab.value.copy(selection = TextRange(idx, idx + q.length))
        scrollTo = idx
        return true
    }

    fun replaceOne(tab: EditorTab) {
        val sel = tab.value.selection
        val q = findText
        if (q.isEmpty()) return
        val selected = if (sel.collapsed) "" else tab.value.text.substring(sel.min, sel.max)
        if (selected.equals(q, ignoreCase = true)) {
            val t = tab.value.text.replaceRange(sel.min, sel.max, replaceText)
            onEdit(tab, TextFieldValue(t, TextRange(sel.min + replaceText.length)))
        }
        findNext(tab)
    }

    fun replaceAll(tab: EditorTab) {
        val q = findText
        if (q.isEmpty()) return
        val before = tab.value.text
        val count = Regex(Regex.escape(q), RegexOption.IGNORE_CASE).findAll(before).count()
        if (count == 0) { say("No match for \"$q\""); return }
        val after = Regex(Regex.escape(q), RegexOption.IGNORE_CASE).replace(before, Regex.escapeReplacement(replaceText))
        onEdit(tab, TextFieldValue(after, TextRange(0)))
        say("Replaced $count matches")
    }

    // ------------------------------------------------------------------ git

    var changes by mutableStateOf<ChangeSet?>(null)

    fun refreshGit() {
        saveAll()
        meta = store.loadMeta(project)
        work("Checking changes…") { changes = store.changes(project) }
    }

    fun openGit() {
        screen = Screen.Git
        changes = null
        refreshGit()
    }

    fun push(message: String) = work("Pushing…") {
        val c = sync.push(project, message, authorName(), authorEmail(), null) { busy = it }
        meta = store.loadMeta(project)
        changes = store.changes(project)
        say("Pushed commit " + c.take(7))
    }

    fun pull() = work("Pulling…") {
        val r = sync.pull(project) { busy = it }
        meta = store.loadMeta(project)
        reloadTabs()
        changes = store.changes(project)
        if (r.upToDate) say("Already up to date.")
        else say("Pulled: ${r.updated} updated, ${r.deleted} removed" +
            if (r.conflicts.isEmpty()) "" else ". Kept your version of: " + r.conflicts.joinToString(", "))
    }

    fun createRepoAndPush(repoName: String, isPrivate: Boolean) {
        val clean = store.sanitize(repoName)
        if (clean.isBlank()) { say("Enter a repository name."); return }
        work("Creating repository…") {
            sync.createAndLink(project, clean, isPrivate) { busy = it }
            meta = store.loadMeta(project)
            val c = sync.push(project, "Initial commit from NeoDev", authorName(), authorEmail(), null) { busy = it }
            meta = store.loadMeta(project)
            changes = store.changes(project)
            say("Repository created and pushed (" + c.take(7) + ")")
        }
    }

    // ------------------------------------------------------------------ builds

    var workflows by mutableStateOf(listOf<GhWorkflow>())
    var runs by mutableStateOf(listOf<GhRun>())
    var selectedRun by mutableStateOf<GhRun?>(null)
    var jobs by mutableStateOf(listOf<GhJob>())
    var artifacts by mutableStateOf(listOf<GhArtifact>())
    var logText by mutableStateOf<String?>(null)
    var buildAdvice by mutableStateOf<String?>(null)
    var adviceBusy by mutableStateOf(false)
    var downloaded by mutableStateOf<DownloadedFile?>(null)
    private var pollJob: Job? = null

    fun openBuild() {
        saveAll()
        meta = store.loadMeta(project)
        screen = Screen.Build
        selectedRun = null
        jobs = emptyList()
        artifacts = emptyList()
        logText = null
        buildAdvice = null
        downloaded = null
        if (!meta.linked) { say("Push this project to GitHub first (Git screen)."); return }
        work("Loading builds…") {
            workflows = api.listWorkflows(meta.owner, meta.repo)
            runs = api.listRuns(meta.owner, meta.repo)
            runs.firstOrNull()?.let { selectRunNow(it) }
        }
        startPolling()
    }

    fun reloadBuilds() = work("Refreshing…") {
        workflows = api.listWorkflows(meta.owner, meta.repo)
        runs = api.listRuns(meta.owner, meta.repo)
        selectedRun?.let { refreshRunDetail(it.id) }
    }

    private fun selectRunNow(run: GhRun) {
        selectedRun = run
        logText = null
        buildAdvice = null
        downloaded = null
        refreshRunDetail(run.id)
    }

    fun selectRun(run: GhRun) {
        selectedRun = run
        logText = null
        buildAdvice = null
        downloaded = null
        jobs = emptyList()
        artifacts = emptyList()
        viewModelScope.launch(Dispatchers.IO) { refreshRunDetail(run.id) }
    }

    private fun refreshRunDetail(id: Long) {
        try {
            val run = api.getRun(meta.owner, meta.repo, id)
            if (selectedRun?.id == id) selectedRun = run
            jobs = api.listJobs(meta.owner, meta.repo, id)
            artifacts = if (!run.running) api.listArtifacts(meta.owner, meta.repo, id) else emptyList()
        } catch (e: Exception) {
            // transient network or API errors are ignored while polling
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(5000)
                try {
                    val r = selectedRun
                    if (screen == Screen.Build && meta.linked && r != null && r.running) {
                        refreshRunDetail(r.id)
                        runs = api.listRuns(meta.owner, meta.repo)
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun runWorkflow(w: GhWorkflow) = work("Starting ${w.name}…") {
        api.dispatch(meta.owner, meta.repo, w.id, meta.branch)
        Thread.sleep(3500)
        runs = api.listRuns(meta.owner, meta.repo)
        runs.firstOrNull()?.let { selectRunNow(it) }
        say("Build started")
    }

    fun cancelRun(r: GhRun) = work("Cancelling…") {
        api.cancelRun(meta.owner, meta.repo, r.id)
        Thread.sleep(1500)
        refreshRunDetail(r.id)
        runs = api.listRuns(meta.owner, meta.repo)
    }

    fun addWorkflow(kind: String) = work("Adding build workflow…") {
        val wrapper = File(store.dir(project), "gradlew").exists()
        val text = if (kind == "apk") Workflows.androidApk(wrapper, 17) else Workflows.gradleJar(wrapper, 21)
        val rel = ".github/workflows/neodev-$kind.yml"
        val f = File(store.dir(project), rel)
        f.parentFile?.mkdirs()
        f.writeText(text)
        sync.push(project, "Add NeoDev $kind build workflow", authorName(), authorEmail(), setOf(rel)) { busy = it }
        meta = store.loadMeta(project)
        Thread.sleep(1500)
        workflows = api.listWorkflows(meta.owner, meta.repo)
        say("Workflow added. You can run it now.")
    }

    fun loadFailedLogs() = work("Loading logs…") {
        val failed = jobs.filter { it.conclusion == "failure" || it.conclusion == "cancelled" }
        val targets = if (failed.isEmpty()) jobs else failed
        val sb = StringBuilder()
        for (j in targets) {
            val log = api.jobLog(meta.owner, meta.repo, j.id)
            sb.append("=== ").append(j.name).append(" ===\n").append(log.takeLast(12000)).append("\n")
        }
        logText = sb.toString().ifBlank { "No log available." }
    }

    fun askBuildAdvice() {
        val log = logText ?: return
        if (adviceBusy) return
        adviceBusy = true
        buildAdvice = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                buildAdvice = gemini.ask(
                    AI_SYSTEM, emptyList(),
                    "My GitHub Actions build failed. Here is the end of the log. Explain the cause in two sentences, " +
                        "then give the exact file change that fixes it.\n\n```\n" + log.takeLast(14000) + "\n```"
                )
            } catch (e: Exception) {
                buildAdvice = "⚠ " + (e.message ?: "AI error")
            } finally {
                adviceBusy = false
            }
        }
    }

    fun downloadArtifact(a: GhArtifact) = work("Downloading ${a.name}…") {
        val zip = File(ctx.cacheDir, "artifact-${a.id}.zip")
        try {
            api.downloadArtifact(meta.owner, meta.repo, a.id, zip)
            val run = selectedRun
            val prefix = project + (if (run != null) "-" + run.number else "")
            val d = ApkTools.deliver(ctx, zip, prefix)
            downloaded = d
            say(if (d.savedTo != null) "Saved to " + d.savedTo else "Downloaded. Use Share to export it.")
        } finally {
            zip.delete()
        }
    }

    // ------------------------------------------------------------------ builder chat

    val builderMessages = mutableStateListOf<ChatMsg>()
    var builderBusy by mutableStateOf(false)
    var builderKind by mutableStateOf("generic")
    var lastChange by mutableStateOf<List<FileChange>?>(null)

    fun hasAiKey(): Boolean = prefs.effectiveGeminiKey().isNotBlank()

    fun startBuilder() {
        saveAll()
        builderKind = BuilderEngine.kindOf(store, project)
        builderMessages.clear()
        val saved = store.loadChat(project)
        if (saved.isEmpty()) {
            builderMessages.add(ChatMsg(false, BuilderEngine.greeting(builderKind, project, prefs.githubLogin)))
        } else {
            builderMessages.addAll(saved)
        }
        lastChange = null
        screen = Screen.Builder
    }

    fun sendBuilder(text: String, display: String? = null) {
        if (builderBusy || text.isBlank()) return
        if (!hasAiKey()) { say("Add your Gemini key in Settings first."); return }
        saveAll()
        val history = builderMessages.toList()
        builderMessages.add(ChatMsg(true, display ?: text))
        builderBusy = true
        val name = project
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prompt = text + "\n\n" + BuilderEngine.context(store, name)
                val raw = gemini.askJson(BuilderEngine.system(BuilderEngine.kindOf(store, name), name), history, prompt)
                val parsed = BuilderEngine.parse(raw)
                val result = BuilderEngine.apply(store, name, parsed)
                lastChange = if (result.changes.isEmpty()) null else result.changes
                val sb = StringBuilder(parsed.reply.ifBlank { "Done." })
                if (result.changes.isNotEmpty()) {
                    sb.append("\n\nFiles:")
                    for (c in result.changes) {
                        val mark = if (c.old == null) "+" else if (c.new == null) "-" else "~"
                        sb.append("\n").append(mark).append(" ").append(c.path)
                    }
                }
                if (result.skipped.isNotEmpty()) {
                    sb.append("\n\nSkipped unsafe paths: ").append(result.skipped.joinToString(", "))
                }
                builderMessages.add(ChatMsg(false, sb.toString()))
                store.saveChat(name, builderMessages.toList())
                if (project == name) {
                    refreshTree()
                    reloadTabs()
                }
            } catch (e: Exception) {
                builderMessages.add(ChatMsg(false, "⚠ " + (e.message ?: "Something went wrong.")))
            } finally {
                builderBusy = false
            }
        }
    }

    fun undoLastChange() {
        val c = lastChange ?: return
        BuilderEngine.revert(store, project, c)
        lastChange = null
        builderMessages.add(ChatMsg(false, "Reverted the last change."))
        store.saveChat(project, builderMessages.toList())
        refreshTree()
        reloadTabs()
    }

    /** One tap: create the GitHub repo if needed, push, start the workflow and show the run. */
    fun buildNow() = work("Preparing build…") {
        saveAll()
        var m = store.loadMeta(project)
        if (!m.linked) {
            sync.createAndLink(project, store.sanitize(project), true) { busy = it }
            m = store.loadMeta(project)
        }
        meta = m
        if (!store.changes(project).isEmpty) {
            sync.push(project, "Update from NeoDev", authorName(), authorEmail(), null) { busy = it }
        }
        meta = store.loadMeta(project)

        val wfName = File(store.dir(project), ".github/workflows").listFiles()
            ?.map { it.name }?.firstOrNull { it.startsWith("neodev-") }
            ?: throw AppException("This project has no build workflow yet. Open Build and add one.")
        var wf: GhWorkflow? = null
        for (i in 1..6) {
            busy = "Waiting for GitHub…"
            wf = api.listWorkflows(meta.owner, meta.repo).firstOrNull { it.path.endsWith(wfName) }
            if (wf != null) break
            Thread.sleep(2500)
        }
        val w = wf ?: throw AppException("GitHub hasn't registered the workflow yet. Open Build and press Refresh.")
        busy = "Starting build…"
        api.dispatch(meta.owner, meta.repo, w.id, meta.branch)
        Thread.sleep(3500)
        workflows = api.listWorkflows(meta.owner, meta.repo)
        runs = api.listRuns(meta.owner, meta.repo)
        jobs = emptyList()
        artifacts = emptyList()
        selectedRun = null
        logText = null
        buildAdvice = null
        downloaded = null
        screen = Screen.Build
        runs.firstOrNull()?.let { selectRunNow(it) }
        startPolling()
    }

    fun fixBuildWithBuilder() {
        val log = logText ?: return
        startBuilder()
        sendBuilder(
            "The GitHub Actions build failed. Fix the project so it builds, changing as little as needed. " +
                "Error log:\n```\n" + log.takeLast(10000) + "\n```",
            "Fix the failed build"
        )
    }

    // ------------------------------------------------------------------ AI

    val aiMessages = mutableStateListOf<ChatMsg>()
    var aiBusy by mutableStateOf(false)
    var aiOpen by mutableStateOf(false)

    private fun fileContext(): String {
        val t = currentTab ?: return "(no file is open)"
        val sel = t.value.selection
        val selected = if (sel.collapsed) "" else t.value.text.substring(sel.min, sel.max)
        val sb = StringBuilder()
        sb.append("File: ").append(t.path).append("\n```\n").append(t.value.text.take(40000)).append("\n```")
        if (selected.isNotBlank()) {
            sb.append("\n\nThe user selected this part, focus on it:\n```\n").append(selected.take(8000)).append("\n```")
        }
        return sb.toString()
    }

    private fun ask(display: String, prompt: String) {
        if (aiBusy) return
        val history = aiMessages.toList()
        aiMessages.add(ChatMsg(true, display))
        aiBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                aiMessages.add(ChatMsg(false, gemini.ask(AI_SYSTEM, history, prompt)))
            } catch (e: Exception) {
                aiMessages.add(ChatMsg(false, "⚠ " + (e.message ?: "AI error")))
            } finally {
                aiBusy = false
            }
        }
    }

    fun aiExplain() = ask("Explain this file", "Explain what this code does, briefly and clearly.\n\n" + fileContext())

    fun aiFix() = ask(
        "Find and fix problems",
        "Find bugs or errors in this code and fix them. Reply with a short list of what was wrong, " +
            "then the full corrected file in one code block.\n\n" + fileContext()
    )

    fun aiAsk(question: String) = ask(question, question + "\n\n" + fileContext())

    fun extractCode(text: String): String? {
        val m = Regex("```[A-Za-z0-9+#_.-]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).find(text) ?: return null
        return m.groupValues[1].trimEnd('\n')
    }

    fun applyAiCode(text: String) {
        val t = currentTab ?: run { say("Open a file first."); return }
        val code = extractCode(text) ?: run { say("No code block in this answer."); return }
        replaceWholeFile(t, code)
        say("Applied to ${t.name}. Undo is available.")
    }

    companion object {
        const val AI_SYSTEM =
            "You are the coding assistant inside NeoDev, a mobile code editor. Answer concisely. " +
                "When you change code, return the full updated file in a single fenced code block."
    }
}
