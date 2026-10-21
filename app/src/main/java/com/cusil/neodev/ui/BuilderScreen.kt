package com.cusil.neodev.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.cusil.neodev.AppViewModel
import com.cusil.neodev.BuilderEngine
import com.cusil.neodev.Screen

@Composable
fun BuilderScreen(vm: AppViewModel) {
    var input by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current
    val state = rememberLazyListState()
    val msgs = vm.builderMessages

    LaunchedEffect(msgs.size, vm.builderBusy) {
        if (msgs.isNotEmpty()) state.animateScrollToItem(msgs.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Header("Builder  ·  " + vm.project, { vm.back() }) {
            SmallBtn("Editor") { vm.back() }
            SmallBtn("Build", enabled = !vm.builderBusy) { vm.buildNow() }
        }

        if (!vm.hasAiKey()) {
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add your Gemini key to chat with NeoDev.", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "It's free and takes a minute. Paste it in Settings, then come back here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { vm.go(Screen.Settings) }) { Text("Open Settings") }
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            state = state,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(msgs.size) { idx ->
                val m = msgs[idx]
                Card(
                    Modifier.fillMaxWidth()
                        .padding(start = if (m.fromUser) 36.dp else 0.dp, end = if (m.fromUser) 0.dp else 12.dp)
                        .border(
                            1.dp,
                            if (m.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        if (!m.fromUser) {
                            Text("NeoDev", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        }
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                        if (!m.fromUser && idx > 0) {
                            SmallBtn("Copy") { clip.setText(AnnotatedString(m.text)) }
                        }
                    }
                }
            }
            if (msgs.size <= 1 && vm.hasAiKey()) {
                items(BuilderEngine.suggestions(vm.builderKind)) { idea ->
                    OutlinedButton(
                        onClick = { vm.sendBuilder(idea) },
                        enabled = !vm.builderBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(idea) }
                }
            }
            if (vm.builderBusy) {
                item {
                    Row(
                        Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            "NeoDev is writing. Big projects can take up to a minute.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (vm.lastChange != null && !vm.builderBusy) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                SmallBtn("Undo last change") { vm.undoLastChange() }
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
                placeholder = { Text("Describe your app or a change") },
                maxLines = 4,
                enabled = vm.hasAiKey()
            )
            Button(
                onClick = {
                    val q = input.trim()
                    if (q.isNotEmpty()) { vm.sendBuilder(q); input = "" }
                },
                enabled = !vm.builderBusy && vm.hasAiKey()
            ) { Text("Send") }
        }
    }
}
