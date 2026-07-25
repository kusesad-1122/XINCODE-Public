package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R

private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Border: Color @Composable get() = LocalXinColors.current.divider
private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

@Composable
fun InlineCodeBlock(language: String?, code: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    Column(
        modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(0.5.dp, Border)
            .background(LocalXinColors.current.bgElevated)
    ) {
        // Language label + copy button row
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                language ?: "code",
                fontSize = 9.sp,
                fontFamily = JetBrainsMono,
                color = Sub
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(code)) },
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "复制",
                    modifier = Modifier.size(14.dp),
                    tint = Sub
                )
            }
        }
        // Code content
        Box(Modifier.fillMaxWidth().horizontalScroll(scrollState).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                code,
                fontSize = 11.sp,
                fontFamily = JetBrainsMono,
                color = Ink,
                lineHeight = 16.sp
            )
        }
    }
}