package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Mono = FontFamily.Monospace

/**
 * 局域网设备:列出同一个 Wi-Fi 下其它开着 XINCODE 的设备。
 *
 * 用途是「知道家里/办公室还有哪台在跑」——后续设备间互传会话或配置可以接在这上面。
 */
@Composable
fun LanDiscoveryScreen(onBack: () -> Unit) {
    val xc = LocalXinColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var peers by remember { mutableStateOf<List<LanDiscovery.Peer>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            peers = LanDiscovery.discover(ctx)
            scanning = false
            scanned = true
        }
    }

    // 进页面先把应答监听拉起来,否则对方搜不到本机;然后自动扫一次。
    LaunchedEffect(Unit) {
        LanDiscovery.startResponder(ctx)
        scan()
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 13.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Spacer(Modifier.weight(1f))
            Text("局域网设备", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink)
            Spacer(Modifier.weight(1f))
            Text(if (scanning) "扫描中" else "扫描",
                fontSize = 12.sp, fontFamily = Mono,
                color = if (scanning) xc.faint else xc.green,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { scan() })
        }

        Text(
            "在同一个 Wi-Fi 下,并且对方也打开着 XINCODE 才能被发现。用移动数据或设备隔离(AP 隔离)的网络搜不到。",
            fontSize = 10.sp, fontFamily = Mono, color = xc.faint, lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (peers.isEmpty() && scanned && !scanning) {
                item {
                    Text("没有发现其它设备。", fontSize = 12.sp, fontFamily = Mono, color = xc.sub,
                        modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            items(peers.size) { i ->
                val p = peers[i]
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(xc.bgElevated).padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.model.ifBlank { p.deviceName },
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
                            color = xc.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (p.isSelf) {
                            Box(Modifier.clip(RoundedCornerShape(10.dp))
                                .background(xc.green.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)) {
                                Text("本机", fontSize = 10.sp, fontFamily = Mono, color = xc.green)
                            }
                        }
                    }
                    Text(p.address, fontSize = 11.sp, fontFamily = Mono, color = xc.sub,
                        modifier = Modifier.padding(top = 4.dp))
                    val meta = buildList {
                        if (p.version.isNotBlank()) add("XINCODE ${p.version}")
                        if (p.androidVersion.isNotBlank()) add("Android ${p.androidVersion}")
                    }.joinToString("  ·  ")
                    if (meta.isNotBlank()) {
                        Text(meta, fontSize = 10.sp, fontFamily = Mono, color = xc.faint,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}
