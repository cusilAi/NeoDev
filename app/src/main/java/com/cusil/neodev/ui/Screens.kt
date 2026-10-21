package com.cusil.neodev.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cusil.neodev.AppViewModel
import com.cusil.neodev.Screen
import com.cusil.neodev.Templates

@Composable
fun HomeScreen(vm: AppViewModel) {
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Header("NeoDev", null) {
            SmallBtn("Settings") { vm.go(Screen.Settings) }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { vm.go(Screen.Clone); vm.loadRepos() },
                modifier = Modifier.weight(1f)
            ) { Text("Clone from GitHub") }
            OutlinedButton(
                onClick = { vm.go(Screen.NewProject) },
                modifier = Modifier.weight(1f)
            ) { Text("New project") }
        }
        if (vm.prefs.githubToken.isBlank()) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable { vm.go(Screen.Settings) }
            ) {
                Text("Add a GitHub token in Settings to clone, push and build.", Modifier.padding(14.dp))
            }
        }
        if (vm.projects.isEmpty()) {
            Text(
                "No projects yet. Clone a repository or start a new project.",
                Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(vm.projects, key = { it }) { name ->
                val meta = remember(name, vm.screen) { vm.store.loadMeta(name) }
                var menu by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().clickable { vm.openProject(name) }) {
                    Row(
                        Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (meta.linked) meta.owner + "/" + meta.repo else "Local only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            TextButton(onClick = { menu = true }) { Text("⋮") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Delete from this phone") },
                                    onClick = { menu = false; deleteTarget = name }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val target = deleteTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete $target?") },
            text = { Text("This removes the local copy only. Anything already pushed stays on GitHub.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteProject(target); deleteTarget = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val p = vm.prefs
    val uri = LocalUriHandler.current
    var token by remember { mutableStateOf(p.githubToken) }
    var gem by remember { mutableStateOf(p.geminiKey) }
    var model by remember { mutableStateOf(p.model) }
    var name by remember { mutableStateOf(p.authorName) }
    var email by remember { mutableStateOf(p.authorEmail) }
    var fs by remember { mutableStateOf(vm.fontSize) }

    Column(Modifier.fillMaxSize()) {
        Header("Settings", { vm.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionLabel("GitHub")
            OutlinedTextField(
                token, { token = it; p.githubToken = it },
                label = { Text("Personal access token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "The token needs the repo and workflow scopes. It stays on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.testToken() }) { Text("Test token") }
                OutlinedButton(onClick = {
                    uri.openUri("https://github.com/settings/tokens/new?scopes=repo,workflow&description=NeoDev")
                }) { Text("Create token") }
            }
            OutlinedTextField(
                name, { name = it; p.authorName = it },
                label = { Text("Commit author name (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                email, { email = it; p.authorEmail = it },
                label = { Text("Commit author email (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("AI helper (Gemini)")
            Text(
                if (p.hasBuiltInKey()) "This build has a built-in key, so AI works without adding your own."
                else "This build has no built-in key. Paste your own Gemini key below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                gem, { gem = it; p.geminiKey = it },
                label = { Text("Your own Gemini key (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                model, { model = it; p.model = it },
                label = { Text("Model") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = { uri.openUri("https://aistudio.google.com/apikey") }) {
                Text("Get a free key")
            }
            Text(
                "Free-tier prompts may be used by Google to improve its products. Avoid sending secrets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionLabel("Editor")
            Text("Font size: $fs")
            Slider(
                value = fs.toFloat(),
                onValueChange = { fs = it.toInt(); vm.fontSize = fs; p.fontSize = fs },
                valueRange = 11f..22f
            )
        }
    }
}

@Composable
fun CloneScreen(vm: AppViewModel) {
    var query by remember { mutableStateOf("") }
    val shown = vm.repos.filter { it.name.contains(query, ignoreCase = true) }

    Column(Modifier.fillMaxSize()) {
        Header("Clone from GitHub", { vm.back() }) { SmallBtn("Reload") { vm.loadRepos() } }
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text("Search your repositories") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
        if (vm.repos.isEmpty() && vm.busy == null) {
            Text(
                "No repositories loaded. Check your token in Settings.",
                Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(shown, key = { it.owner + "/" + it.name }) { r ->
                Card(Modifier.fillMaxWidth().clickable { vm.cloneRepo(r) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            r.owner + "/" + r.name + if (r.isPrivate) "  (private)" else "",
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (r.description.isNotBlank()) {
                            Text(
                                r.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NewProjectScreen(vm: AppViewModel) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("android") }

    Column(Modifier.fillMaxSize()) {
        Header("New project", { vm.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                name, { name = it },
                label = { Text("Project name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SectionLabel("Template")
            for ((id, label) in Templates.kinds) {
                Row(
                    Modifier.fillMaxWidth().clickable { kind = id },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = kind == id, onClick = { kind = id })
                    Spacer(Modifier.width(4.dp))
                    Text(label, Modifier.weight(1f))
                }
            }
            Button(onClick = { vm.createProject(name, kind) }, modifier = Modifier.fillMaxWidth()) {
                Text("Create project")
            }
        }
    }
}
