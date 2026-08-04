package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import com.xincode.app.R
import com.xincode.data.AppDatabase
import com.xincode.data.CronJobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg: Color @Composable get() = LocalXinColors.current.bg
private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Green: Color @Composable get() = LocalXinColors.current.green
private val Red: Color @Composable get() = LocalXinColors.current.red
private val Border: Color @Composable get() = LocalXinColors.current.border
private val JetBrainsMono = XinUiFont

/**
 * Hermes-⑦ 定时任务管理页:列出 cron_jobs,可开关/删除。任务由模型经 cronjob 工具创建,
 * WorkManager 周期 tick 执行。此页让用户能看/管这些无人值守的自动化。
 */
@Composable
fun CronJobsScreen(
    database: AppDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf<List<CronJobEntity>>(emptyList()) }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(reloadTick) {
        jobs = withContext(Dispatchers.IO) { database.cronJobDao().getAll() }
    }

    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        XinPageHeader(
            title = "定时任务",
            subtitle = "由 Agent 创建并在后台周期执行",
            onBack = onBack
        )
        Spacer(Modifier.height(8.dp))

        if (jobs.isEmpty()) {
            Text("(暂无定时任务。对 agent 说\"每天…\"/\"2 小时后…\"即可创建)", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint)
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(jobs, key = { it.id }) { job ->
                Column(Modifier.fillMaxWidth().border(0.5.dp, Border).padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("#${job.id} ${job.name}", fontSize = 13.sp, fontFamily = JetBrainsMono, color = Ink,
                            modifier = Modifier.weight(1f))
                        // 开关
                        Text(if (job.enabled) "● 开" else "○ 关", fontSize = 11.sp, fontFamily = JetBrainsMono,
                            color = if (job.enabled) Green else Sub,
                            modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        database.cronJobDao().update(job.copy(enabled = !job.enabled, updatedAt = System.currentTimeMillis()))
                                    }
                                    reloadTick++
                                }
                            })
                        Spacer(Modifier.width(12.dp))
                        Text("删除", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Red,
                            modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { database.cronJobDao().deleteById(job.id) }
                                    reloadTick++
                                }
                            })
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(job.prompt, fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    val next = if (job.nextRunAt > 0) fmt.format(Date(job.nextRunAt)) else "—"
                    Text("${job.scheduleSpec} · 下次 $next · ${if (job.lastStatus.isBlank()) "未运行" else job.lastStatus}",
                        fontSize = 9.sp, fontFamily = JetBrainsMono, color = Faint)
                }
            }
        }
    }
}
