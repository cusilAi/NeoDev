package com.cusil.neodev.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cusil.neodev.AppViewModel
import com.cusil.neodev.EditorTab
import com.cusil.neodev.SyntaxTransformation
import kotlinx.coroutines.launch

@Composable
fun WorkspaceScreen(vm: AppViewModel) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var closing by remember { mutableStateOf<Int?>(null) }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(300.dp)) {
                FileTree(
                    vm,
                    onOpen = { vm.openFile(it); scope.launch { drawer.close() } },
                    onCreate = { parent, isDir -> creating = parent to isDir },
                    onRename = { renaming = it },
                    onDelete = { deleting = it }
                )
            }
        }
    ) {
        val tab = vm.currentTab
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmallBtn("←") { vm.back() }
                    SmallBtn("☰") { scope.launch { drawer.open() } }
                    Text(
                        vm.project,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    SmallBtn("Save") { vm.saveAll(); vm.say("Saved") }
                    SmallBtn("Git") { vm.openGit() }
                    SmallBtn("Build") { vm.openBuild() }
                    SmallBtn("AI") { vm.aiOpen = !vm.aiOpen }
                }
            }

            if (vm.tabs.isNotEmpty()) {
                LazyRow(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                    itemsIndexed(vm.tabs) { i, t ->
                        TabChip(
                            t, i == vm.activeTab,
                            onClick = { vm.activeTab = i },
                            onClose = { if (t.dirty) closing = i else vm.closeTab(i, false) }
                        )
                    }
                }
            }

            if (vm.findOpen && tab != null) {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            vm.findText, { vm.findText = it },
                            Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("Find") }
                        )
                        Button(onClick = { vm.findNext(tab) }) { Text("Next") }
                        TextButton(onClick = { vm.findOpen = false }) { Text("✕") }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            vm.replaceText, { vm.replaceText = it },
                            Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("Replace with") }
                        )
                        OutlinedButton(onClick = { vm.replaceOne(tab) }) { Text("Replace") }
                        OutlinedButton(onClick = { vm.replaceAll(tab) }) { Text("All") }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == null) {
                    Text(
                        "Open a file from the ☰ menu.",
                        Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    key(tab.path) { EditorView(vm, tab, Modifier.fillMaxSize()) }
                }
            }

            if (tab != null) {
                SymbolBar(vm, tab)
                StatusLine(tab)
            }

            if (vm.aiOpen) {
                AiPanel(vm, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    creating?.let { (parent, isDir) ->
        TextDialog(if (isDir) "New folder" else "New file", "", "Create", { creating = null }) { text ->
            vm.newEntry(parent, text, isDir)
            creating = null
        }
    }

    renaming?.let { rel ->
        TextDialog("Rename", rel.substringAfterLast('/'), "Rename", { renaming = null }) { text ->
            vm.renameEntry(rel, text)
            renaming = null
        }
    }

    deleting?.let { rel ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${rel.substringAfterLast('/')}?") },
            text = { Text("This can't be undone on this phone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteEntry(rel); deleting = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }

    closing?.let { i ->
        val t = vm.tabs.getOrNull(i)
        if (t == null) {
            closing = null
        } else {
            AlertDialog(
                onDismissRequest = { closing = null },
                title = { Text("Unsaved changes") },
                text = { Text("Save ${t.name} before closing?") },
                confirmButton = {
                    Row {
                        TextButton(onClick = { vm.closeTab(i, false); closing = null }) { Text("Discard") }
                        TextButton(onClick = { vm.closeTab(i, true); closing = null }) { Text("Save") }
                    }
                },
                dismissButton = { TextButton(onClick = { closing = null }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun TabChip(t: EditorTab, active: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        color = bg,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t.name + if (t.dirty) " •" else "",
                Modifier.clickable { onClick() }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                "×",
                Modifier.clickable { onClose() }.padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileTree(
    vm: AppViewModel,
    onOpen: (String) -> Unit,
    onCreate: (String, Boolean) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                vm.project,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SmallBtn("+ File") { onCreate("", false) }
            SmallBtn("+ Folder") { onCreate("", true) }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.rows, key = { it.rel }) { row ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { if (row.isDir) vm.toggleDir(row.rel) else onOpen(row.rel) }
                        .padding(start = (12 + row.depth * 16).dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val mark = if (row.isDir) (if (row.expanded) "▾ " else "▸ ") else "    "
                    Text(
                        mark + row.name,
                        Modifier.weight(1f).padding(vertical = 10.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box {
                        Text("⋮", Modifier.clickable { menu = true }.padding(horizontal = 12.dp, vertical = 10.dp))
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (row.isDir) {
                                DropdownMenuItem(
                                    text = { Text("New file here") },
                                    onClick = { menu = false; onCreate(row.rel, false) }
                                )
                                DropdownMenuItem(
                                    text = { Text("New folder here") },
                                    onClick = { menu = false; onCreate(row.rel, true) }
                                )
                            }
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename(row.rel) })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete(row.rel) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorView(vm: AppViewModel, tab: EditorTab, modifier: Modifier) {
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val transformation = remember(tab.ext) { SyntaxTransformation(tab.ext) }

    LaunchedEffect(vm.scrollTo) {
        val off = vm.scrollTo
        if (off != null) {
            val l = layout
            if (l != null) {
                val safe = off.coerceIn(0, tab.value.text.length)
                val line = l.getLineForOffset(safe)
                scroll.animateScrollTo((l.getLineTop(line) - 150f).toInt().coerceAtLeast(0))
            }
            vm.scrollTo = null
        }
    }

    Box(modifier.verticalScroll(scroll).padding(horizontal = 8.dp, vertical = 4.dp)) {
        BasicTextField(
            value = tab.value,
            onValueChange = { vm.onEdit(tab, it) },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 400.dp),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = vm.fontSize.sp,
                lineHeight = (vm.fontSize * 1.45f).sp,
                color = Color(0xFFE6E8EE)
            ),
            cursorBrush = SolidColor(Color(0xFF8AB4FF)),
            visualTransformation = transformation,
            onTextLayout = { layout = it },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false
            )
        )
    }
}

@Composable
private fun SymbolBar(vm: AppViewModel, tab: EditorTab) {
    val pairs = listOf("{}", "()", "[]", "\"\"", "<>")
    val singles = listOf(";", ":", "=", "/", "#", "_", "'", "\\", ".", ",")
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
    ) {
        SmallBtn("Undo") { vm.undo(tab) }
        SmallBtn("Redo") { vm.redo(tab) }
        SmallBtn("Find") { vm.findOpen = !vm.findOpen }
        SmallBtn("⇥") { vm.insertText(tab, "    ") }
        for (p in pairs) SmallBtn(p) { vm.insertText(tab, p, 1) }
        for (s in singles) SmallBtn(s) { vm.insertText(tab, s) }
    }
}

@Composable
private fun StatusLine(tab: EditorTab) {
    val text = tab.value.text
    val pos = tab.value.selection.end.coerceIn(0, text.length)
    val line = text.substring(0, pos).count { it == '\n' } + 1
    val col = pos - (text.lastIndexOf('\n', pos - 1) + 1) + 1
    val total = text.count { it == '\n' } + 1
    Text(
        "Ln $line, Col $col  ·  $total lines  ·  ${tab.ext.ifEmpty { "text" }}" + if (tab.dirty) "  ·  unsaved" else "",
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AiPanel(vm: AppViewModel, modifier: Modifier) {
    var input by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current
    val state = rememberLazyListState()

    LaunchedEffect(vm.aiMessages.size, vm.aiBusy) {
        if (vm.aiMessages.isNotEmpty()) state.animateScrollToItem(vm.aiMessages.size - 1)
    }

    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "AI  ·  ${vm.prefs.model}",
                Modifier.weight(1f).padding(start = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SmallBtn("Explain", enabled = !vm.aiBusy) { vm.aiExplain() }
            SmallBtn("Fix", enabled = !vm.aiBusy) { vm.aiFix() }
            SmallBtn("Builder") { vm.startBuilder() }
            SmallBtn("✕") { vm.aiOpen = false }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            state = state,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (vm.aiMessages.isEmpty()) {
                item {
                    Text(
                        "Ask about the open file, or tap Explain or Fix. Select code first to focus on a part.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            items(vm.aiMessages.size) { idx ->
                val m = vm.aiMessages[idx]
                Card(
                    Modifier.fillMaxWidth().border(
                        1.dp,
                        if (m.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(12.dp)
                    )
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(m.text, style = MaterialTheme.typography.bodySmall)
                        if (!m.fromUser) {
                            Row {
                                SmallBtn("Copy") { clip.setText(AnnotatedString(m.text)) }
                                if (vm.extractCode(m.text) != null) {
                                    SmallBtn("Apply code to file") { vm.applyAiCode(m.text) }
                                }
                            }
                        }
                    }
                }
            }
            if (vm.aiBusy) {
                item { Text("Thinking…", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                input, { input = it },
                Modifier.weight(1f),
                placeholder = { Text("Ask about this file") },
                maxLines = 3
            )
            Button(
                onClick = { val q = input.trim(); if (q.isNotEmpty()) { vm.aiAsk(q); input = "" } },
                enabled = !vm.aiBusy
            ) { Text("Send") }
        }
    }
}
