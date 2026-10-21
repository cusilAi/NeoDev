package com.cusil.neodev.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cusil.neodev.AppViewModel
import com.cusil.neodev.ChangeSet

@Composable
fun GitScreen(vm: AppViewModel) {
    var msg by remember { mutableStateOf("") }
    var repoName by remember { mutableStateOf(vm.project) }
    var priv by remember { mutableStateOf(true) }
    val m = vm.meta
    val ch = vm.changes

    Column(Modifier.fillMaxSize()) {
        Header("Git  ·  " + vm.project, { vm.back() }) {
            SmallBtn("Refresh") { vm.refreshGit() }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!m.linked) {
                Text("This project only exists on your phone. Create a GitHub repository to push it.")
                OutlinedTextField(
                    repoName, { repoName = it },
                    label = { Text("Repository name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = priv, onCheckedChange = { priv = it })
                    Spacer(Modifier.width(10.dp))
                    Text(if (priv) "Private repository" else "Public repository")
                }
                Button(
                    onClick = { vm.createRepoAndPush(repoName, priv) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create repository and push") }
            } else {
                Text(m.owner + "/" + m.repo + "  @  " + m.branch, style = MaterialTheme.typography.titleMedium)
                if (ch == null) {
                    Text("Checking changes…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (ch.isEmpty) {
                    Text("No changes. Everything is pushed.", color = GoodColor)
                } else {
                    ChangeList(ch)
                    OutlinedTextField(
                        msg, { msg = it },
                        label = { Text("Commit message") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { vm.push(msg.ifBlank { "Update from NeoDev" }); msg = "" },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Commit and push ${ch.total} change" + if (ch.total == 1) "" else "s") }
                }
                OutlinedButton(onClick = { vm.pull() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Pull latest from GitHub")
                }
                Text(
                    "If both you and GitHub changed the same file, Pull keeps your version, and pushing then replaces the remote one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChangeList(ch: ChangeSet) {
    val lines = ArrayList<Pair<String, androidx.compose.ui.graphics.Color>>()
    for (f in ch.added) lines.add("+ $f" to GoodColor)
    for (f in ch.modified) lines.add("~ $f" to WarnColor)
    for (f in ch.deleted) lines.add("- $f" to BadColor)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((i, l) in lines.withIndex()) {
            if (i >= 80) {
                Text("… and ${lines.size - 80} more", color = MaterialTheme.colorScheme.onSurfaceVariant)
                break
            }
            Text(l.first, color = l.second, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}
