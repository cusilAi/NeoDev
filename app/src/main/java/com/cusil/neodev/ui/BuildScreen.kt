package com.cusil.neodev.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cusil.neodev.ApkTools
import com.cusil.neodev.AppViewModel
import com.cusil.neodev.GhRun

private fun runColor(r: GhRun): Color = when {
    r.running -> WarnColor
    r.conclusion == "success" -> GoodColor
    else -> BadColor
}

private fun stepMark(status: String, conclusion: String): String = when {
    status == "in_progress" -> "…"
    status != "completed" -> "·"
    conclusion == "success" -> "✓"
    conclusion == "skipped" -> "–"
    else -> "✗"
}

private fun sizeText(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)

@Composable
fun BuildScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val m = vm.meta

    Column(Modifier.fillMaxSize()) {
        Header("Build  ·  " + vm.project, { vm.back() }) {
            SmallBtn("Refresh", enabled = m.linked) { vm.reloadBuilds() }
        }

        if (!m.linked) {
            Text(
                "Push this project to GitHub first. Open the Git screen and create a repository.",
                Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(m.owner + "/" + m.repo + "  @  " + m.branch, style = MaterialTheme.typography.titleMedium)

            // ---------------------------------------------------------- workflows
            SectionLabel("Workflows")
            if (vm.workflows.isEmpty()) {
                Text(
                    "No build workflow found in this repository. Add one below, then run it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            for (w in vm.workflows) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                w.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = { vm.runWorkflow(w) }) { Text("Run") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.addWorkflow("apk") }, modifier = Modifier.weight(1f)) {
                    Text("Add APK workflow")
                }
                OutlinedButton(onClick = { vm.addWorkflow("jar") }, modifier = Modifier.weight(1f)) {
                    Text("Add JAR workflow")
                }
            }
            Text(
                "A workflow must contain workflow_dispatch to be started from here. The ones added by NeoDev do.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---------------------------------------------------------- runs
            SectionLabel("Recent runs")
            if (vm.runs.isEmpty()) {
                Text("No runs yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (r in vm.runs) {
                val selected = vm.selectedRun?.id == r.id
                Card(Modifier.fillMaxWidth().clickable { vm.selectRun(r) }) {
                    Row(
                        Modifier.padding(12.dp)
                            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("#" + r.number + "  " + r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(
                                r.branch + "  ·  " + r.created.replace('T', ' ').removeSuffix("Z"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(r.label, color = runColor(r), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // ---------------------------------------------------------- selected run
            val run = vm.selectedRun
            if (run != null) {
                SectionLabel("Run #" + run.number)
                Text(run.label, color = runColor(run), style = MaterialTheme.typography.titleMedium)
                if (run.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { vm.cancelRun(run) }) { Text("Cancel run") }
                }
                for (j in vm.jobs) {
                    Text(
                        stepMark(j.status, j.conclusion) + " " + j.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    for (s in j.steps) {
                        Text(
                            "    " + stepMark(s.status, s.conclusion) + " " + s.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedButton(onClick = { uri.openUri(run.url) }) { Text("Open on GitHub") }

                // ------------------------------------------------------ downloads
                if (!run.running && run.conclusion == "success") {
                    SectionLabel("Downloads")
                    val usable = vm.artifacts.filter { !it.expired }
                    if (usable.isEmpty()) {
                        Text(
                            "This run finished without a downloadable file. Make sure the workflow uploads an artifact.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    for (a in usable) {
                        Button(onClick = { vm.downloadArtifact(a) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Download " + a.name + "  (" + sizeText(a.size) + ")")
                        }
                    }
                    val d = vm.downloaded
                    if (d != null) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(d.file.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (d.savedTo != null) "Saved to " + d.savedTo else "Not saved to Downloads. Use Share.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (d.isApk) {
                                        Button(onClick = { ApkTools.install(ctx, d.file) }) { Text("Install") }
                                    }
                                    OutlinedButton(onClick = { ApkTools.share(ctx, d.file) }) { Text("Share") }
                                }
                            }
                        }
                    }
                }

                // ------------------------------------------------------ failures
                if (!run.running && run.conclusion != "success") {
                    SectionLabel("What went wrong")
                    OutlinedButton(onClick = { vm.loadFailedLogs() }) { Text("Show error log") }
                    val log = vm.logText
                    if (log != null) {
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                log,
                                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(10.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                        Button(
                            onClick = { vm.fixBuildWithBuilder() },
                            enabled = vm.hasAiKey(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Let NeoDev fix it") }
                        OutlinedButton(onClick = { vm.askBuildAdvice() }, enabled = !vm.adviceBusy) {
                            Text(if (vm.adviceBusy) "Asking AI…" else "Only explain the error")
                        }
                    }
                    val advice = vm.buildAdvice
                    if (advice != null) {
                        Card(Modifier.fillMaxWidth()) {
                            Text(advice, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
