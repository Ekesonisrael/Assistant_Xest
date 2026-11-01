package com.xestlab.assistant.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xestlab.assistant.ui.theme.Shapes8px
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.WarningRed
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel

@Composable
fun WeekScreen(viewModel: AssistantViewModel) {
    val context = LocalContext.current
    val summary by viewModel.weeklySummary.collectAsState()
    val goal by viewModel.goals.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshWeeklySummary()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Weekly Activity Scorecard",
                        fontSize = 18.dp.value.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Real-time count of actions logged this week against your studio targets.",
                        fontSize = 13.dp.value.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        item {
            WeeklyTargetCard(
                title = "Outreach messages",
                done = summary.messagesCount,
                target = summary.messagesTarget
            )
        }

        item {
            WeeklyTargetCard(
                title = "Conversations held",
                done = summary.conversationsCount,
                target = summary.conversationsTarget
            )
        }

        item {
            WeeklyTargetCard(
                title = "Quotes sent",
                done = summary.quotesCount,
                target = summary.quotesTarget
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Consistency and Discipline",
                        fontSize = 15.dp.value.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Content pieces published:", fontSize = 13.dp.value.sp)
                        Text(text = "${summary.contentCount}", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Study blocks completed:", fontSize = 13.dp.value.sp)
                        Text(text = "${summary.studyCount}", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    val text = viewModel.getWeeklyReviewPlainText()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Weekly Summary", text))
                    viewModel.showToast("Weekly summary copied to clipboard")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = Shapes8px.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TealAccent,
                    contentColor = Color.White
                )
            ) {
                Text(text = "Copy weekly summary", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun WeeklyTargetCard(title: String, done: Int, target: Int) {
    val statusWord = when {
        done > target -> "Ahead"
        done == target || done >= target * 0.9 -> "On track"
        else -> "Behind"
    }

    val statusColor = when (statusWord) {
        "Behind" -> WarningRed
        else -> TealAccent
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = Shapes8px,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    fontSize = 14.dp.value.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$done / $target completed",
                    fontSize = 13.dp.value.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Text(
                text = statusWord,
                fontSize = 13.dp.value.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }
    }
}
