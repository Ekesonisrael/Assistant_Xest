package com.xestlab.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xestlab.assistant.ui.screens.HomeScreen
import com.xestlab.assistant.ui.screens.NotesScreen
import com.xestlab.assistant.ui.screens.PlanScreen
import com.xestlab.assistant.ui.screens.SettingsScreen
import com.xestlab.assistant.ui.screens.SetupScreen
import com.xestlab.assistant.ui.theme.BgDark
import com.xestlab.assistant.ui.theme.BorderDark
import com.xestlab.assistant.ui.theme.SurfaceDark
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.TealAccentContainer
import com.xestlab.assistant.ui.theme.TextMutedDark
import com.xestlab.assistant.ui.theme.TextPrimaryDark
import com.xestlab.assistant.ui.theme.XestlabTheme
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XestlabTheme {
                val assistantViewModel: AssistantViewModel = viewModel()
                AssistantRootApp(viewModel = assistantViewModel)
            }
        }
    }
}

private data class NavTab(
    val label: String,
    val icon: ImageVector
)

@Composable
private fun AssistantRootApp(viewModel: AssistantViewModel) {
    val loadedPrefs by viewModel.userPrefsLoaded.collectAsState()
    val statusBanner by viewModel.statusBanner.collectAsState()

    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    var showSetupOverlay by rememberSaveable { mutableStateOf(false) }

    val prefs = loadedPrefs
    if (prefs == null) {
        // Initial DataStore snapshot loading
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
        )
        return
    }

    // First-launch gate: prompt for user's name and save in Jetpack DataStore
    if (!prefs.isFirstLaunchComplete || prefs.userName.isBlank()) {
        SetupScreen(
            viewModel = viewModel,
            isReopenedFromSettings = false
        )
        return
    }

    if (showSetupOverlay) {
        SetupScreen(
            viewModel = viewModel,
            isReopenedFromSettings = true,
            onClose = { showSetupOverlay = false }
        )
        return
    }

    val tabs = listOf(
        NavTab("Home", Icons.Default.Home),
        NavTab("Plan", Icons.Default.DateRange),
        NavTab("Notes", Icons.Default.Info),
        NavTab("Settings", Icons.Default.Settings)
    )

    Scaffold(
        containerColor = BgDark,
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 0.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = currentTab == index,
                        onClick = { currentTab = index },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TealAccent,
                            selectedTextColor = TealAccent,
                            indicatorColor = TealAccentContainer,
                            unselectedIconColor = TextMutedDark,
                            unselectedTextColor = TextMutedDark
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!statusBanner.isNullOrBlank()) {
                Surface(
                    color = TealAccentContainer,
                    border = BorderStroke(1.dp, BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusBanner.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimaryDark,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Dismiss",
                            style = MaterialTheme.typography.labelMedium,
                            color = TealAccent,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .clickable { viewModel.clearStatusBanner() }
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (currentTab) {
                    0 -> HomeScreen(viewModel = viewModel)
                    1 -> PlanScreen(viewModel = viewModel)
                    2 -> NotesScreen(viewModel = viewModel)
                    3 -> SettingsScreen(
                        viewModel = viewModel,
                        onOpenSetup = { showSetupOverlay = true }
                    )
                }
            }
        }
    }
}
