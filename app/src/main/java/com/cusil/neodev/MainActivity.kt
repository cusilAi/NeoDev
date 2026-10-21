package com.cusil.neodev

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cusil.neodev.ui.BusyOverlay
import com.cusil.neodev.ui.BuilderScreen
import com.cusil.neodev.ui.BuildScreen
import com.cusil.neodev.ui.CloneScreen
import com.cusil.neodev.ui.GitScreen
import com.cusil.neodev.ui.HomeScreen
import com.cusil.neodev.ui.NeoDevTheme
import com.cusil.neodev.ui.NewProjectScreen
import com.cusil.neodev.ui.SettingsScreen
import com.cusil.neodev.ui.WorkspaceScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NeoDevTheme {
                Root()
            }
        }
    }
}

@Composable
fun Root(vm: AppViewModel = viewModel()) {
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snack.showSnackbar(m)
            vm.message = null
        }
    }

    BackHandler(enabled = vm.screen != Screen.Home) { vm.back() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (vm.screen) {
            Screen.Home -> HomeScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
            Screen.Clone -> CloneScreen(vm)
            Screen.NewProject -> NewProjectScreen(vm)
            Screen.Workspace -> WorkspaceScreen(vm)
            Screen.Git -> GitScreen(vm)
            Screen.Build -> BuildScreen(vm)
            Screen.Builder -> BuilderScreen(vm)
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
        val label = vm.busy
        if (label != null) BusyOverlay(label)
    }
}
