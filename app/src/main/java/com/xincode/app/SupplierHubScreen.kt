package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.AppDatabase
import com.xincode.provider.OpenAiClient
import com.xincode.security.KeystoreProvider

/** Unified supplier entry: saved configurations and the preset supplier market share one page. */
@Composable
fun SupplierHubScreen(
    database: AppDatabase,
    keystore: KeystoreProvider,
    openAiClient: OpenAiClient,
    onBack: () -> Unit,
    onConfigChanged: () -> Unit = {},
    initialTab: Int = 0
) {
    val xc = LocalXinColors.current
    var selectedTab by remember { mutableStateOf(initialTab.coerceIn(0, 1)) }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(
            title = "模型与供应商",
            subtitle = "管理 API 密钥、运行模型与探索供应商市场",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(xc.bgElevated)
                .border(0.5.dp, xc.border, RoundedCornerShape(14.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SupplierHubTab(
                title = "我的配置",
                selected = selectedTab == 0,
                modifier = Modifier.weight(1f),
                onClick = { selectedTab = 0 }
            )
            SupplierHubTab(
                title = "供应商市场",
                selected = selectedTab == 1,
                modifier = Modifier.weight(1f),
                onClick = { selectedTab = 1 }
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                0 -> SupplierConfigScreen(
                    database = database,
                    keystore = keystore,
                    openAiClient = openAiClient,
                    onBack = {},
                    onConfigChanged = onConfigChanged,
                    showHeader = false
                )
                else -> ModelMarketScreen(
                    database = database,
                    keystore = keystore,
                    openAiClient = openAiClient,
                    onBack = {},
                    onConfigChanged = onConfigChanged,
                    showHeader = false
                )
            }
        }
    }
}

@Composable
private fun SupplierHubTab(
    title: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val xc = LocalXinColors.current
    Box(
        modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) xc.activeBg else xc.bgElevated)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = XinUiFont,
            color = if (selected) xc.green else xc.sub
        )
    }
}
