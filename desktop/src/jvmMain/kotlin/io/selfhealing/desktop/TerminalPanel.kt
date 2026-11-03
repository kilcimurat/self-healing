package io.selfhealing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.selfhealing.core.model.LogSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun render(l: LogLine): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = Palette.Muted.copy(alpha = 0.55f))) { append(timeFmt.format(Instant.ofEpochMilli(l.time))) }
    append(' ')
    withStyle(SpanStyle(color = sourceColor(l.source), fontWeight = FontWeight.Bold)) {
        append(l.source.name.padEnd(7))
    }
    append(' ')
    withStyle(SpanStyle(color = if (l.source == LogSource.SYSTEM) Palette.Muted else Palette.OnSurface)) { append(l.text) }
}

@Composable
fun TerminalPanel(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().background(Palette.Terminal)) {
        // Header
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Terminal", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Palette.OnSurface)
            Box(Modifier.weight(1f))
            val label = if (vm.status == EngineStatus.LISTENING) harnessLabel(vm) else null
            if (label != null) {
                Text(label, fontFamily = Mono, fontSize = 12.sp, color = Palette.Accent)
            }
        }
        // Tabs
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface).horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TabChip("Sistem", vm.activeTab == SYSTEM_TAB) { vm.selectTab(SYSTEM_TAB) }
            for (c in vm.sortedCrashes.reversed()) {
                val title = c.report.title.let { if (it.length > 22) it.take(21) + "…" else it }
                TabChip(title, vm.activeTab == c.report.id) { vm.selectTab(c.report.id) }
            }
        }
        TerminalLines(vm.linesFor(vm.activeTab), vm.activeTab)
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (selected) Palette.SurfaceHigh else Palette.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text, fontSize = 12.sp, maxLines = 1,
            color = if (selected) Palette.Accent else Palette.Muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun TerminalLines(lines: List<LogLine>, tabKey: String) {
    val state = rememberLazyListState()
    var follow by remember(tabKey) { mutableStateOf(true) }
    var programmatic by remember(tabKey) { mutableStateOf(false) }

    fun atBottom(): Boolean {
        val info = state.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return true
        return last.index >= info.totalItemsCount - 1
    }

    LaunchedEffect(tabKey) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (programmatic) return@collect
            if (scrolling) follow = atBottom() else if (atBottom()) follow = true
        }
    }
    LaunchedEffect(tabKey, lines.size, follow) {
        if (follow && lines.isNotEmpty()) {
            programmatic = true
            try {
                state.scrollToItem(lines.size - 1)
            } finally {
                programmatic = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (lines.isEmpty()) {
            Text(
                "Henüz çıktı yok.", color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
        SelectionContainer {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                items(lines, key = { it.id }) { l ->
                    Text(render(l), style = CodeStyle, softWrap = true)
                }
            }
        }
        if (!follow) {
            Surface(
                color = Palette.Accent, shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).clickable { follow = true },
            ) {
                Text(
                    "↓ en alta", color = androidx.compose.ui.graphics.Color(0xFF04201A), fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
