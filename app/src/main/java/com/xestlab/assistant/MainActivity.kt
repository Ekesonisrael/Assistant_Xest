package com.xestlab.assistant

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.xestlab.assistant.ui.screens.*
import com.xestlab.assistant.ui.theme.XestlabTheme
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels()
    private var requestedTab by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedTab = intent?.getStringExtra("OPEN_SCREEN")

        setContent {
            XestlabTheme(darkTheme = true) {
                AppRoot(viewModel = viewModel, requestedTab = requestedTab)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab = intent.getStringExtra("OPEN_SCREEN")
    }
}

private enum class Tab(val label: String) {
    HOME("Home"),
    PLAN("Plan"),
    NOTES("Notes"),
    SETTINGS("Settings")
}

@Composable
private fun AppRoot(viewModel: AssistantViewModel, requestedTab: String?) {
    val context = LocalContext.current
    val storedSettings by viewModel.repository.settings.collectAsState(initial = null)
    val toast by viewModel.toastMessage.collectAsState()

    var tab by remember { mutableStateOf(Tab.HOME) }
    var showSetup by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    LaunchedEffect(requestedTab) {
        when (requestedTab?.lowercase()) {
            "home" -> tab = Tab.HOME
            "plan" -> tab = Tab.PLAN
            "notes" -> tab = Tab.NOTES
            "settings" -> tab = Tab.SETTINGS
            "wakeup" -> tab = Tab.HOME
        }
    }

    val settings = storedSettings
    if (settings == null) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }

    if (!settings.isFirstRunComplete || showSetup) {
        SetupScreen(viewModel = viewModel, onComplete = { showSetup = false })
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                Tab.values().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {},
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(viewModel = viewModel)
                Tab.PLAN -> PlanScreen(viewModel = viewModel)
                Tab.NOTES -> NotesScreen(viewModel = viewModel)
                Tab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel,
                    onNavigateToSetup = { showSetup = true }
                )
            }
        }
    }
}
