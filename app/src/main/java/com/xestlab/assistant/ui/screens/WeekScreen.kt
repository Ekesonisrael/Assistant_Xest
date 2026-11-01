package com.xestlab.assistant.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xestlab.assistant.ui.theme.BgDark
import com.xestlab.assistant.ui.theme.BorderDark
import com.xestlab.assistant.ui.theme.CardShape8
import com.xestlab.assistant.ui.theme.SurfaceDark
import com.xestlab.assistant.ui.theme.SurfaceSubtleDark
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.TextMutedDark
import com.xestlab.assistant.ui.theme.TextPrimaryDark
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel

@Composable
fun WeekScreen(viewModel: AssistantViewModel) {
    val context = LocalContext.current
    val summary by viewModel.weeklySummary.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshWeeklySummary()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "7-DAY EXECUTION SUMMARY",
            style = MaterialTheme.typography.labelMedium,
            color = TealAccent
        )

        MetricProgressCard(
            label = "Outreach Messages Sent",
            current = summary.messagesCount,
            target = summary.messagesTarget
        )
        MetricProgressCard(
            label = "Active Lead Conversations",
            current = summary.conversationsCount,
            target = summary.conversationsTarget
        )
        MetricProgressCard(
            label = "Proposals & Quotes Sent",
            current = summary.quotesCount,
            target = summary.quotesTarget
        )
        MetricProgressCard(
            label = "Content Pieces Published",
            current = summary.contentCount,
            target = 5
        )
        MetricProgressCard(
            label = "Focused Study Blocks",
            current = summary.studyCount,
            target = 5
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    val plain = viewModel.buildWeeklyReviewPlainText()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Weekly Review", plain))
                    viewModel.setStatusBanner("Copied 7-day summary to clipboard.")
                },
                shape = CardShape8,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                modifier = Modifier.weight(1f)
            ) {
                Text("Copy Review", color = BgDark, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = {
                    val plain = viewModel.buildWeeklyReviewPlainText()
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, plain)
                    }
                    context.startActivity(Intent.createChooser(share, "Share Weekly Review"))
                },
                shape = CardShape8,
                border = BorderStroke(1.dp, BorderDark),
                modifier = Modifier.weight(1f)
            ) {
                Text("Share", color = TextPrimaryDark)
            }
        }
    }
}

@Composable
private fun MetricProgressCard(
    label: String,
    current: Int,
    target: Int
) {
    val safeTarget = target.coerceAtLeast(1)
    val fraction = (current.toFloat() / safeTarget.toFloat()).coerceIn(0f, 1f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape8,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderDark)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    text = "$current / $target",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (current >= target) TealAccent else TextMutedDark
                )
            }
            LinearProgressIndicator(
                progress = { fraction },
                color = TealAccent,
                trackColor = SurfaceSubtleDark,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
