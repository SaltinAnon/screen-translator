package com.saltin.screentranslator.ui

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.saltin.screentranslator.core.AppSettings
import com.saltin.screentranslator.core.BubbleMergeMode
import com.saltin.screentranslator.core.Defaults
import com.saltin.screentranslator.core.ServiceState
import com.saltin.screentranslator.core.SettingsStore
import com.saltin.screentranslator.core.SupportedLanguages
import com.saltin.screentranslator.core.TranslateMode
import com.saltin.screentranslator.ocr.OfflineModelWarmup
import com.saltin.screentranslator.ocr.ScriptMode
import com.saltin.screentranslator.ocr.ScriptSupport
import com.saltin.screentranslator.ocr.TextOrientation
import com.saltin.screentranslator.service.TranslationServiceController
import com.saltin.screentranslator.translate.LlmDiagnostics
import com.saltin.screentranslator.translate.LlmExchangeLog
import com.saltin.screentranslator.translate.LlmProviders
import com.saltin.screentranslator.translate.LlmRequestExtras
import com.saltin.screentranslator.translate.TranslatorFactory
import com.saltin.screentranslator.translate.offline.OfflineEngineKind
import com.saltin.screentranslator.translate.offline.OfflineModelCatalog
import com.saltin.screentranslator.translate.offline.OfflineModelStore
import com.saltin.screentranslator.translate.offline.PackProgress
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 设置页的分页。
 *
 * v1.4.0 之前所有卡片挤在一个长页面里，想改个悬浮球大小得从顶部一路滑到底 ——
 * 这一版按「用户此刻想干什么」把它拆成五页，底部导航直接跳转：
 *
 * - [HOME]      启动/停止、当前状态、权限、教程 —— 用之前/出问题时看这里
 * - [TRANSLATE] 怎么触发、用哪种翻译模式 —— 决定「翻译怎么发生」
 * - [RECOGNIZE] 语言、识别器、排版方向、OCR 参数 —— 决定「翻得准不准」
 * - [MODEL]     服务商与密钥、离线模型、接口诊断 —— 决定「用谁翻」
 * - [DISPLAY]   悬浮球、译文外观、译文框合并 —— 决定「翻出来长什么样」
 */
private enum class SettingsTab(val label: String, val title: String, val icon: ImageVector) {
    HOME("首页", "屏幕实时翻译", Icons.Filled.Home),
    TRANSLATE("翻译", "翻译方式", Icons.Filled.Translate),
    RECOGNIZE("识别", "识别与准确度", Icons.Filled.TextFields),
    MODEL("模型", "模型服务", Icons.Filled.Cloud),
    DISPLAY("显示", "外观与悬浮球", Icons.Filled.Tune),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTranslateNow: () -> Unit,
    onClearOverlay: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenTutorial: () -> Unit,
) {
    val context = LocalContext.current
    val settings by settingsStore.state.collectAsState()
    val running by ServiceState.running.collectAsState()
    val status by ServiceState.status.collectAsState()
    val stats by ServiceState.stats.collectAsState()
    val error by ServiceState.lastError.collectAsState()
    val hint by ServiceState.hint.collectAsState()
    val lastBlocks by ServiceState.lastBlocks.collectAsState()

    // 从系统设置页返回后需要重新判断权限
    val lifecycleOwner = LocalLifecycleOwner.current
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notificationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                notificationGranted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val cloudMissing = settings.mode != TranslateMode.ON_DEVICE &&
        TranslatorFactory.effectiveApiKey(settings).isBlank()

    // 分页状态跟着 Activity 重建一起保存，转屏/回到前台不会跳回第一页
    var tab by rememberSaveable { mutableStateOf(SettingsTab.HOME) }
    // 五个页面共用一个滚动状态，但要保证「切页后从顶部开始看」而不是续着上一页的偏移
    val scroll = rememberScrollState()
    LaunchedEffect(tab) { scroll.scrollTo(0) }

    fun save(next: AppSettings) = settingsStore.update { next }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.title) },
                actions = {
                    TextButton(onClick = onOpenTutorial) {
                        Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("使用教程")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                SettingsTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                // ---------------------------------------------------- 首页：用之前/出问题时看这里
                SettingsTab.HOME -> {
                    StatusCard(
                        running = running,
                        status = status,
                        error = error,
                        hint = hint,
                        stats = stats,
                        lastBlocks = lastBlocks,
                    )

                    RunControls(
                        running = running,
                        autoTranslate = settings.autoTranslate,
                        onStart = onStart,
                        onStop = onStop,
                        onTranslateNow = onTranslateNow,
                        onClear = onClearOverlay,
                    )

                    if (cloudMissing) {
                        MissingKeyCard(
                            providerLabel = TranslatorFactory.resolveProvider(settings).label,
                            onOpenTutorial = onOpenTutorial,
                        )
                    }

                    PermissionCard(
                        overlayGranted = overlayGranted,
                        notificationGranted = notificationGranted,
                        onRequestOverlay = onRequestOverlayPermission,
                        onRequestNotification = onRequestNotificationPermission,
                    )

                    TutorialCard(seen = settings.tutorialSeen, onOpen = onOpenTutorial)

                    Text(
                        text = "提示：停止后系统会保留投屏授权记录，下次启动仍需重新确认（Android 14 起的安全要求）。" +
                            "设置按类别分在下面五个页面里 —— 改外观去「显示」，换模型去「模型」，翻不准去「识别」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ---------------------------------------------------- 翻译方式：翻译怎么发生
                SettingsTab.TRANSLATE -> {
                    TriggerCard(settings = settings, onChange = ::save)
                    ModeCard(
                        selected = settings.mode,
                        providerSupportsVision = TranslatorFactory.resolveProvider(settings).supportsVision,
                        attachScreenshot = settings.attachScreenshot,
                        onSelect = { mode -> save(settings.copy(mode = mode)) },
                    )
                }

                // ---------------------------------------------------- 识别与准确度：翻得准不准
                SettingsTab.RECOGNIZE -> {
                    LanguageCard(settings = settings, onChange = ::save)
                    PerformanceCard(settings = settings, onChange = ::save)
                }

                // ---------------------------------------------------- 模型服务：用谁翻
                SettingsTab.MODEL -> {
                    ModelServiceCard(
                        settings = settings,
                        onChange = ::save,
                        onOpenTutorial = onOpenTutorial,
                    )
                    OfflineEngineCard(settings = settings, onChange = ::save)
                    // ML Kit 那一套的模型是 Google 按语言对分发的，走它自己的下载流程，
                    // 只有选到「标准 · ML Kit」这一档时才需要展示。另外两档由上面的
                    // 卡片直接管理 ONNX 模型文件。
                    if (settings.offlineEngine == OfflineEngineKind.ML_KIT) {
                        OfflineModelCard(settings = settings, onChange = ::save)
                    }
                    DiagnosticsCard()
                }

                // ---------------------------------------------------- 外观与悬浮球：翻出来长什么样
                SettingsTab.DISPLAY -> {
                    BubbleCard(settings = settings, onChange = ::save)
                    AppearanceCard(settings = settings, onChange = ::save)
                    BubbleMergeCard(settings = settings, onChange = ::save)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 译文框合并（一个对话泡一个框）。
 *
 * 放在「显示」页而不是「翻译」页，因为它不改译文内容、只改**画出来几个框**：
 * 作用在翻译之后的最终屏幕坐标上，对端侧/混合/多模态三种模式一视同仁。
 */
@Composable
private fun BubbleMergeCard(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    SectionCard("译文框合并（一个对话泡一个框）") {
        BubbleMergeMode.entries.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onChange(settings.copy(bubbleMerge = mode)) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = settings.bubbleMerge == mode,
                    onClick = { onChange(settings.copy(bubbleMerge = mode)) },
                )
                Column(Modifier.weight(1f).padding(top = 4.dp)) {
                    Text(
                        mode.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (settings.bubbleMerge == mode) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        mode.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (settings.bubbleMerge != BubbleMergeMode.OFF) {
            HorizontalDivider()
            val presets = Defaults.BUBBLE_GAP_PRESETS
            val presetIndex = presets
                .indexOfFirst { it >= settings.bubbleGapRatio - 0.001f }
                .takeIf { it >= 0 } ?: presets.lastIndex
            LabeledSlider(
                label = "合并强度",
                value = presetIndex.toFloat(),
                range = 0f..presets.lastIndex.toFloat(),
                steps = (presets.size - 2).coerceAtLeast(0),
                valueText = bubbleLevelLabel(settings.bubbleGapRatio),
                onValueChange = { raw ->
                    val index = raw.roundToInt().coerceIn(0, presets.lastIndex)
                    onChange(settings.copy(bubbleGapRatio = presets[index]))
                },
            )
        }

        Text(
            "原文的一句话常常横跨好几行（竖排日文则是好几列），OCR 会把它们分别交出来，" +
                "于是同一个对话泡里冒出好几个译文框，互相压着挡住画面 —— 竖排那种又窄又高的框" +
                "为了塞下横排译文还得先横向撑开，叠得更厉害。\n" +
                "打开这项会在**翻译完成之后**把紧挨着的框合成一个：包围盒取并集（也就是那个泡子本身），" +
                "译文按阅读顺序拼成一段（竖排按从右往左的列序），再整体折行排版 —— " +
                "所以一个泡子只会出现一个框，不会再有重叠遮挡。\n" +
                "「自动」只在版面看起来是竖排时才动手，普通界面和网页保持原来的逐行覆盖；" +
                "看横排漫画、外语字幕截图这类整屏都是对话的场景，可以选「总是合并」更干净。\n" +
                "发现「不同泡子被粘成一个框」就把合并强度调保守；" +
                "发现「同一个泡子还是好几块」就调激进。顶部状态栏会显示这次合并掉几个框。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 把间距阈值翻译成人话，跟「合并段落」的档位说法保持一致 */
private fun bubbleLevelLabel(ratio: Float): String = when {
    ratio <= 0.65f -> "保守"
    ratio <= 0.8f -> "偏保守"
    ratio <= 0.92f -> "标准（推荐）"
    ratio <= 1.08f -> "偏激进"
    ratio <= 1.25f -> "激进"
    else -> "最激进（排得很松的漫画用）"
}

// ------------------------------------------------------------------ 状态

@Composable
private fun StatusCard(
    running: Boolean,
    status: String,
    error: String?,
    hint: String?,
    stats: com.saltin.screentranslator.translate.PipelineStats?,
    lastBlocks: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (running) Color(0xFFE3F4EA) else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (running) Color(0xFF2E7D5B) else Color(0xFF9AA3B2)),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = if (running) "运行中" else "已停止",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (running && lastBlocks > 0) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "本次译文 $lastBlocks 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(status, style = MaterialTheme.typography.bodyMedium)

            if (hint != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFB26A00),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8A5B00),
                    )
                }
            }

            if (error != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFB3261E),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB3261E),
                    )
                }
            }

            if (stats != null) {
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatCell("OCR", "${stats.ocrMs}ms")
                    StatCell("翻译", "${stats.translateMs}ms")
                    StatCell("总耗时", "${stats.totalMs}ms")
                    StatCell("译文块", "${stats.renderedBlocks}")
                }
                Text(
                    "识别 ${stats.ocrBlocks} 行 → 保留 ${stats.keptBlocks} 段" +
                        if (stats.mergedLines > 0) "（合并掉 ${stats.mergedLines} 行）" else "" +
                        if (stats.bubbleMerged > 0) " → 泡子合并掉 ${stats.bubbleMerged} 个框" else "" +
                        " · 过滤 ${stats.droppedBlocks} · 缓存命中 ${stats.cacheHits}" +
                        " · 上报 ${stats.requestChars} 字 · 引擎 ${stats.engineId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RunControls(
    running: Boolean,
    autoTranslate: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTranslateNow: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStart,
                enabled = !running,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("开始屏幕翻译")
            }
            OutlinedButton(
                onClick = onStop,
                enabled = running,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB3261E)),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("停止")
            }
        }

        if (running && !autoTranslate) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onTranslateNow,
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("翻译一次")
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("清空译文")
                }
            }
            Text(
                "也可以直接点屏幕上的悬浮球来翻译，效果一样。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ 教程入口

@Composable
private fun TutorialCard(seen: Boolean, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        colors = CardDefaults.cardColors(
            containerColor = if (seen) MaterialTheme.colorScheme.surface else Color(0xFFEAF1FB),
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (seen) "使用教程" else "第一次用？先花 2 分钟看教程",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (seen) "悬浮球怎么用、模式怎么选、Key 怎么申请，都在里面"
                    else "10 小节讲清楚：悬浮球手势、三种模式怎么选、API Key 怎么申请、离线模型怎么下、出问题怎么办",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MissingKeyCard(providerLabel: String, onOpenTutorial: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4E0)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "还没配置 $providerLabel 的 API Key",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF8A5B00),
            )
            Text(
                "不影响使用：启动后会临时改用「端侧离线」翻译，点了就有结果。" +
                    "想用云端大模型提升质量，到下面的「模型服务」里填一个 Key 即可。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8A5B00),
            )
            TextButton(onClick = onOpenTutorial) { Text("看教程第 5 节：配置云端模型") }
        }
    }
}

@Composable
private fun PermissionCard(
    overlayGranted: Boolean,
    notificationGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestNotification: () -> Unit,
) {
    SectionCard("权限") {
        PermissionRow(
            title = "显示在其他应用上层（悬浮球）",
            granted = overlayGranted,
            actionLabel = "去授权",
            onAction = onRequestOverlay,
        )
        HorizontalDivider()
        PermissionRow(
            title = "通知权限（前台服务常驻通知）",
            granted = notificationGranted,
            actionLabel = "去授权",
            onAction = onRequestNotification,
        )
        if (!overlayGranted) {
            Text(
                "这个权限不开，屏幕上不会出现悬浮球，译文也画不出来 —— 这是最常见的新手坑。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB3261E),
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = if (granted) Color(0xFF2E7D5B) else Color(0xFFB3261E),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!granted) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        } else {
            Text(
                "已授权",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF2E7D5B),
            )
        }
    }
}

// ------------------------------------------------------------------ 触发方式

@Composable
private fun TriggerCard(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    SectionCard("怎么触发翻译") {
        SwitchRow(
            title = "自动连续翻译",
            subtitle = "关闭（默认）= 点一下悬浮球翻译一次；打开 = 按下面的间隔一直刷新，适合看直播字幕、长网页",
            checked = settings.autoTranslate,
            onCheckedChange = { onChange(settings.copy(autoTranslate = it)) },
        )
        HorizontalDivider()
        LabeledSlider(
            label = "取帧间隔",
            value = settings.captureIntervalMs.toFloat(),
            range = 300f..3000f,
            steps = 26,
            valueText = "${settings.captureIntervalMs} ms",
            onValueChange = { onChange(settings.copy(captureIntervalMs = it.roundToInt().toLong())) },
        )
        if (!settings.autoTranslate) {
            LabeledSlider(
                label = "译文自动消失",
                value = settings.autoClearSeconds.toFloat(),
                range = 0f..30f,
                steps = 29,
                valueText = if (settings.autoClearSeconds == 0) "不自动消失" else "${settings.autoClearSeconds} 秒后",
                onValueChange = { onChange(settings.copy(autoClearSeconds = it.roundToInt())) },
            )
        }
    }
}

// ------------------------------------------------------------------ 模式

@Composable
private fun ModeCard(
    selected: TranslateMode,
    providerSupportsVision: Boolean,
    attachScreenshot: Boolean,
    onSelect: (TranslateMode) -> Unit,
) {
    SectionCard("翻译模式") {
        TranslateMode.entries.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(mode) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = selected == mode, onClick = { onSelect(mode) })
                Column(Modifier.weight(1f).padding(top = 4.dp)) {
                    Text(
                        mode.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected == mode) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (mode == TranslateMode.HYBRID && selected == mode && attachScreenshot &&
                        !providerSupportsVision
                    ) {
                        Text(
                            "当前服务商没有可用的看图模型，这一项会自动走纯文字路径（不影响使用）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A5B00),
                        )
                    }
                }
            }
        }
        Text(
            "不确定选哪个就用第一项之外的推荐模式；只想看懂大意、又在意隐私，就选「端侧离线」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------ 模型服务

@Composable
private fun ModelServiceCard(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onOpenTutorial: () -> Unit,
) {
    val provider = LlmProviders.byId(settings.llmProviderId)
    val effectiveBaseUrl = settings.llmBaseUrl.ifBlank { provider.defaultBaseUrl }
    val effectiveModel = settings.llmModel.ifBlank { provider.defaultModel }

    var keyDraft by remember(settings.llmApiKey) { mutableStateOf(settings.llmApiKey) }
    var urlDraft by remember(settings.llmBaseUrl) { mutableStateOf(settings.llmBaseUrl) }
    var modelDraft by remember(settings.llmModel) { mutableStateOf(settings.llmModel) }

    val scope = rememberCoroutineScope()
    var busyText by remember { mutableStateOf(false) }
    var busyVision by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }

    fun currentDraft(): AppSettings = settings.copy(
        llmApiKey = keyDraft.trim(),
        llmBaseUrl = urlDraft.trim(),
        llmModel = modelDraft.trim(),
    )

    SectionCard("模型服务（云端模式使用）") {
        DropdownField(
            label = "服务商",
            value = provider,
            options = LlmProviders.all,
            optionLabel = { it.label },
            onSelect = { next ->
                // 换服务商时清掉地址和模型，回到该家的默认值
                keyDraft = ""
                urlDraft = ""
                modelDraft = ""
                testResult = null
                testOk = null
                onChange(settings.copy(llmProviderId = next.id, llmBaseUrl = "", llmModel = ""))
            },
        )

        Spacer(Modifier.height(6.dp))
        Text(
            provider.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("API Key（${provider.keyHint}）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = urlDraft,
            onValueChange = { urlDraft = it },
            label = { Text("接口地址 Base URL") },
            placeholder = { Text(effectiveBaseUrl.ifBlank { "https://…/v1" }) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "留空即使用默认地址：$effectiveBaseUrl；直接粘贴完整的 /chat/completions 地址也能识别",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = modelDraft,
            onValueChange = { modelDraft = it },
            label = { Text("模型名") },
            placeholder = { Text(effectiveModel.ifBlank { "例如 gpt-4o-mini" }) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (provider.models.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                provider.models.forEach { preset ->
                    SuggestionChip(text = preset, onClick = { modelDraft = preset })
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "以上是常见模型名，点一下填入；也可以直接手打任意模型名。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !busyText && !busyVision,
                onClick = {
                    val next = currentDraft()
                    onChange(next)
                    busyText = true
                    testResult = null
                    testOk = null
                    scope.launch {
                        try {
                            testResult = LlmDiagnostics.testText(TranslatorFactory.buildConfig(next))
                            testOk = true
                        } catch (t: Throwable) {
                            testResult = t.message ?: t::class.java.simpleName
                            testOk = false
                        } finally {
                            busyText = false
                        }
                    }
                },
            ) {
                if (busyText) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (busyText) "测试中…" else "保存并测试")
            }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(
                enabled = !busyText && !busyVision,
                onClick = {
                    val next = currentDraft()
                    onChange(next)
                    busyVision = true
                    testResult = null
                    testOk = null
                    scope.launch {
                        try {
                            testResult = LlmDiagnostics.testVision(TranslatorFactory.buildConfig(next))
                            testOk = true
                        } catch (t: Throwable) {
                            testResult = t.message ?: t::class.java.simpleName
                            testOk = false
                        } finally {
                            busyVision = false
                        }
                    }
                },
            ) {
                if (busyVision) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (busyVision) "测试中…" else "测试看图")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "「保存并测试」验证 Key / 地址 / 模型名；「测试看图」会现场发一张写着英文单词的图，" +
                "确认这个模型真的能读图（混合模式附带截图、整屏多模态都依赖它）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (testResult != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                testResult!!,
                style = MaterialTheme.typography.bodySmall,
                color = if (testOk == true) Color(0xFF2E7D5B) else Color(0xFFB3261E),
            )
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()

        SwitchRow(
            title = "关闭思考过程",
            subtitle = "思考模型（Qwen3.x、Gemini 2.5、DeepSeek-R 等）会先把思考写一遍再作答，" +
                "既慢又容易把 JSON 撑断、甚至让正文变成空。建议保持开启；" +
                "个别模型不允许关闭时会自动去掉该参数重试。",
            checked = settings.disableThinking,
            onCheckedChange = { onChange(settings.copy(disableThinking = it)) },
        )
        SwitchRow(
            title = "强制 JSON 输出",
            subtitle = "给接口带上 response_format。少数中转网关不认这个字段会返回 400，" +
                "遇到时会自动去掉重试一次，不会让你卡住。",
            checked = settings.forceJsonMode,
            onCheckedChange = { onChange(settings.copy(forceJsonMode = it)) },
        )

        Spacer(Modifier.height(8.dp))
        var extraDraft by remember(settings.extraRequestJson) {
            mutableStateOf(settings.extraRequestJson)
        }
        OutlinedTextField(
            value = extraDraft,
            onValueChange = { extraDraft = it },
            label = { Text("附加请求参数（JSON，可留空）") },
            placeholder = { Text("{\"enable_thinking\": false}") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        val extraError = LlmRequestExtras.validate(extraDraft)
        Text(
            extraError ?: "会合并进请求体，用于处理各家接口的特殊要求。" +
                "例如百炼的 Qwen3 系列要求非流式调用带 {\"enable_thinking\": false}，" +
                "本应用已为该服务商自动注入，一般不用手填。",
            style = MaterialTheme.typography.bodySmall,
            color = if (extraError != null) Color(0xFFB3261E) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row {
            TextButton(
                enabled = extraDraft != settings.extraRequestJson,
                onClick = { onChange(settings.copy(extraRequestJson = extraDraft.trim())) },
            ) { Text("保存附加参数") }
            if (extraDraft.isNotEmpty()) {
                TextButton(onClick = {
                    extraDraft = ""
                    onChange(settings.copy(extraRequestJson = ""))
                }) { Text("清空") }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        SwitchRow(
            title = "混合模式下附带截图",
            subtitle = "开启后会把一张缩小截图一起发给模型，译文更贴合语境；" +
                "纯文本模型（如 DeepSeek）会自动跳过这一步。" +
                "截图被服务商拒绝时（常见于内容审核，返回 402）会自动改用纯文字重试，" +
                "译文不受影响 —— 但想完全避开审核，关掉本项即可。",
            checked = settings.attachScreenshot,
            onCheckedChange = { onChange(settings.copy(attachScreenshot = it)) },
        )
        LabeledSlider(
            label = "上传图片边长上限",
            value = settings.uploadMaxSide.toFloat(),
            range = 640f..1600f,
            steps = 23,
            valueText = "${settings.uploadMaxSide} px",
            onValueChange = { onChange(settings.copy(uploadMaxSide = it.roundToInt())) },
        )
        LabeledSlider(
            label = "上传图片质量",
            value = settings.jpegQuality.toFloat(),
            range = 40f..90f,
            steps = 24,
            valueText = "${settings.jpegQuality}",
            onValueChange = { onChange(settings.copy(jpegQuality = it.roundToInt())) },
        )

        TextButton(onClick = onOpenTutorial) { Text("各家 API Key 去哪申请？看教程第 5 节") }

        Text(
            "建议专门为这个应用申请一个 Key 并在服务商后台设置消费上限；" +
                "混合架构下每帧通常只上传几百字文本 + 一张缩略图。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------ 接口诊断

@Composable
private fun DiagnosticsCard() {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    SectionCard("接口诊断（出问题时看这里）") {
        Text(
            "这里保存最近一次云端请求和模型的原始返回。译文出不来、或者报「没有可用的译文块」时，" +
                "点开看一眼就能定位：是接口不认参数、模型不支持看图，还是模型压根没按格式回。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "查看最近一次交互")
            }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(LlmExchangeLog.describe()))
                    copied = true
                },
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (copied) "已复制" else "复制")
            }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = { LlmExchangeLog.clear() }) { Text("清除") }
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                LlmExchangeLog.describe(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFEAF1FB))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF1B4B8A),
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ 离线模型

/**
 * 端侧离线引擎的两个档选择 + ONNX 模型下载管理（v1.5 新增）。
 *
 * ## 为什么要给用户选择
 *
 * v1.4.5 之前端侧只有 Google ML Kit 一套模型。它的模型**围绕英语**训练：
 * 英语↔其他语言是直接模型，非英语之间（日→中、韩→中）要用英语中转两次，
 * 译文会明显生硬 —— 用户实测反馈就是「离线模型太普通，翻译效果极差」。
 *
 * 换成 Opus-MT 之后是**单语言对直译**，质量高出一档。两档面向不同需求，
 * 谁也替代不了谁，所以做成用户自选而不是替用户决定：
 * 要小要快选 ML Kit；常见方向要质量选 Opus-MT「增强」；方向要多选「全能」。
 *
 * ## 为什么这里要提「日译中」
 *
 * Opus-MT 缺 ja→zh、ko→zh 等东亚方向（Helsinki-NLP 没训）。日译中恰恰是
 * 看日本漫画最需要的那条，所以选到这种方向必须明确说明会**经英语接力** ——
 * 不能静默降级，也不能假装是直译。
 */
@Composable
private fun OfflineEngineCard(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<PackProgress?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    // 下载/删除后 +1，用来强制重算「是否已就绪」与占用空间
    var revision by remember { mutableStateOf(0) }
    var showHostField by remember { mutableStateOf(false) }

    val selected = settings.offlineEngine
    val src = settings.sourceLanguage
    val tgt = settings.targetLanguage

    // 一条翻译路线可能是「一个直接模型」，也可能是「经英语接力的两个模型」
    // （Opus-MT 没有日→中的直接模型，只能用 ja→en + en→zh 接力）
    val route = remember(selected, src, tgt) {
        runCatching { OfflineModelCatalog.routeFor(selected, src, tgt) }.getOrNull()
    }
    val packs = route?.packs.orEmpty()
    val ready = remember(packs, revision) {
        packs.isNotEmpty() && packs.all { OfflineModelStore.isReady(context, it) }
    }
    val presentMb = remember(packs, revision) {
        packs.sumOf { OfflineModelStore.presentBytes(context, it) } / 1048576L
    }
    val routeMb = route?.totalMb ?: 0
    val usedMb = remember(revision) { OfflineModelStore.usedBytes(context) / 1048576L }

    SectionCard("端侧离线引擎（离线模式用哪个模型）") {
        Text(
            "这里选的是「端侧 / 离线模式」下真正干活的模型。三档按**覆盖面**从窄到宽递进，" +
                "换档后立刻生效、不需要重启。三档**都能独立翻译**，越往上可翻的方向越多。" +
                "云端与混合模式不受这里影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        OfflineEngineKind.entries.forEach { kind ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy) { onChange(settings.copy(offlineEngine = kind)) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = selected == kind,
                    onClick = { onChange(settings.copy(offlineEngine = kind)) },
                    enabled = !busy,
                )
                Column(Modifier.weight(1f)) {
                    Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        kind.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))

        when {
            selected == OfflineEngineKind.ML_KIT -> Text(
                "ML Kit 的模型由 Google 按语言对分发，下载入口在下面的「离线翻译（端侧模式）」卡片里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            route == null -> Text(
                if (selected == OfflineEngineKind.OPUS_MT) {
                    "「增强」档没有「${SupportedLanguages.label(src)} → " +
                        "${SupportedLanguages.label(tgt)}」这个方向（直译和英语接力都没有）。" +
                        "想翻这个方向请改选「全能 · Opus-MT 全覆盖」（覆盖更多方向），" +
                        "或把离线引擎换回 ML Kit。"
                } else {
                    "Opus-MT 没有「${SupportedLanguages.label(src)} → ${SupportedLanguages.label(tgt)}」" +
                        "这个方向，也没法用英语接力补出来（上游没训过这一侧的英语方向）。" +
                        "请把离线引擎换回 ML Kit，或改用云端模式。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB3261E),
            )

            else -> {
                Text(
                    "当前方向：${SupportedLanguages.label(src)} → ${SupportedLanguages.label(tgt)}" +
                        "　·　约 ${routeMb}MB",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (route.isPivot) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "这一方向上 Opus-MT 没有直接模型，会用「${route.pivotLabel}」两段接力，" +
                            "所以要下 ${packs.size} 个模型（合计约 ${routeMb}MB）。" +
                            "速度约为直译的一半，个别句子会带上二次转译的偏差。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8A5B00),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        ready -> "已下载完成，可以断网使用。"
                        presentMb > 0L ->
                            "下载了一部分（${presentMb}MB / ${routeMb}MB），点「继续下载」可以接着下。"
                        else -> "还没下载。下载一次可以一直用。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ready) Color(0xFF2E7D5B) else Color(0xFF8A5B00),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    packs.joinToString("　+　") { "${it.label}（${it.totalMb}MB）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                progress?.takeIf { busy }?.let { p ->
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { p.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${p.doneMb}MB / ${p.totalMb}MB　·　${p.currentFile}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) Color(0xFFB3261E) else Color(0xFF2E7D5B),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = !busy && !ready,
                        onClick = {
                            busy = true
                            message = null
                            failed = false
                            scope.launch {
                                val result = OfflineModelStore.downloadAll(
                                    context = context,
                                    packs = packs,
                                    host = settings.offlineModelHost,
                                ) { p -> progress = p }
                                when (result) {
                                    is OfflineModelStore.DownloadResult.Success -> {
                                        message = if (packs.size > 1) {
                                            "两个模型都已下载完成，离线模式现在可以用了。"
                                        } else {
                                            "模型已下载完成，离线模式现在可以用了。"
                                        }
                                        failed = false
                                    }
                                    is OfflineModelStore.DownloadResult.Failed -> {
                                        message = result.reason
                                        failed = true
                                    }
                                }
                                progress = null
                                busy = false
                                revision++
                            }
                        },
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("下载中…")
                        } else {
                            Text(if (ready) "已就绪" else "下载模型")
                        }
                    }
                    if (ready) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                val removed = packs.count { OfflineModelStore.remove(context, it.id) }
                                message = if (packs.size > 1) {
                                    "已删除本地模型文件（$removed / ${packs.size} 个）。"
                                } else {
                                    "已删除本地模型文件。"
                                }
                                failed = false
                                revision++
                            },
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("删除")
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showHostField = !showHostField }) {
                    Text(if (showHostField) "收起下载源设置" else "下载太慢 / 失败？换个下载源")
                }
                if (showHostField) {
                    OutlinedTextField(
                        value = settings.offlineModelHost,
                        onValueChange = { onChange(settings.copy(offlineModelHost = it)) },
                        label = { Text("模型下载源（留空用默认 ${OfflineModelCatalog.DEFAULT_HOST}）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "模型托管在 HuggingFace 上，部分网络下直连不通，所以默认走国内镜像 " +
                            "${OfflineModelCatalog.DEFAULT_HOST}。如果它也慢，可以换成你知道的其他镜像域名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "已占用：约 ${usedMb}MB。每个语言对约 115MB，可按方向单独下载、" +
                        "不再需要时也能单独删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OfflineModelCard(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf<Boolean?>(null) }
    var localStatus by remember { mutableStateOf<OfflineModelWarmup.Status?>(null) }

    val src = settings.sourceLanguage
    val tgt = settings.targetLanguage

    LaunchedEffect(src, tgt) {
        localStatus = runCatching { OfflineModelWarmup.status(src, tgt) }.getOrNull()
    }

    SectionCard("离线翻译（端侧模式）") {
        Text(
            "端侧离线不联网、不上传，也不需要 API Key。前提是先把这一对的本地小模型下载到手机上：" +
                "一个语言对约 ${OfflineModelWarmup.APPROX_MB}MB，下载一次可以一直用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                DropdownField(
                    label = "源语言",
                    value = src,
                    options = SupportedLanguages.entries.map { it.first },
                    optionLabel = { SupportedLanguages.label(it) },
                    onSelect = { onChange(settings.copy(sourceLanguage = it)) },
                )
            }
            Box(Modifier.weight(1f)) {
                DropdownField(
                    label = "目标语言",
                    value = tgt,
                    options = SupportedLanguages.entries.filter { it.first != "auto" }.map { it.first },
                    optionLabel = { SupportedLanguages.label(it) },
                    onSelect = { onChange(settings.copy(targetLanguage = it)) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        val supported = SupportedLanguages.onDeviceSupported(src, tgt)
        Text(
            when {
                !supported -> "这一对语言端侧模型不支持，请换一个语言，或者使用云端模式。"
                localStatus?.ready == true -> "已检测到本机模型，可以断网使用。"
                else -> "本机还没有这一对的模型，点下面的按钮下载。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when {
                !supported -> Color(0xFFB3261E)
                localStatus?.ready == true -> Color(0xFF2E7D5B)
                else -> Color(0xFF8A5B00)
            },
        )

        // ★ 把离线模式的真实能力边界说清楚，避免用户以为是软件坏了
        val qualityWarning = SupportedLanguages.onDeviceQualityWarning(src, tgt)
        if (qualityWarning != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "⚠ $qualityWarning",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8A5B00),
            )
        }
        val ocrScript = ScriptSupport.unsupportedScriptName(settings.scriptMode, src)
        if (ocrScript != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "⚠ 端侧还读不出「$ocrScript」的文字，所以这个源语言在离线模式下用不了 —— " +
                    "哪怕翻译模型支持它。把中文翻成它没问题，读它本身的界面必须用云端多模态。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB3261E),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "识别用的文字系统可以在上面「语言与识别」卡片里看到。离线模式的实际效果：" +
                "英语互译相当可用；其他语言互译因为要经英语中转，质量会明显下降 —— 这是 ML Kit 的固有局限，" +
                "调参数解决不了，只能用云端模型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !busy && supported,
                onClick = {
                    busy = true
                    message = null
                    ok = null
                    scope.launch {
                        try {
                            val result = OfflineModelWarmup.warmup(src, tgt) { progress ->
                                message = progress
                            }
                            message = result
                            ok = true
                            localStatus = runCatching { OfflineModelWarmup.status(src, tgt) }.getOrNull()
                        } catch (t: Throwable) {
                            message = t.message ?: t::class.java.simpleName
                            ok = false
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (busy) "处理中…" else "下载 / 检查离线模型")
            }
            Spacer(Modifier.size(10.dp))
            if (settings.mode != TranslateMode.ON_DEVICE) {
                TextButton(onClick = { onChange(settings.copy(mode = TranslateMode.ON_DEVICE)) }) {
                    Text("切到端侧模式")
                }
            }
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = when (ok) {
                    true -> Color(0xFF2E7D5B)
                    false -> Color(0xFFB3261E)
                    else -> Color(0xFF8A5B00)
                },
            )
        }
    }
}

// ------------------------------------------------------------------ 语言

@Composable
private fun LanguageCard(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val unsupported = ScriptSupport.unsupportedScriptName(settings.scriptMode, settings.sourceLanguage)
    val script = ScriptSupport.resolve(settings.scriptMode, settings.sourceLanguage)

    SectionCard("语言与识别") {
        DropdownField(
            label = "源语言",
            value = settings.sourceLanguage,
            options = SupportedLanguages.entries.map { it.first },
            optionLabel = { SupportedLanguages.label(it) },
            onSelect = { onChange(settings.copy(sourceLanguage = it)) },
        )
        Spacer(Modifier.height(10.dp))
        DropdownField(
            label = "目标语言",
            value = settings.targetLanguage,
            options = SupportedLanguages.entries.filter { it.first != "auto" }.map { it.first },
            optionLabel = { SupportedLanguages.label(it) },
            onSelect = { onChange(settings.copy(targetLanguage = it)) },
        )
        Spacer(Modifier.height(10.dp))
        DropdownField(
            label = "文字系统（OCR 识别器）",
            value = settings.scriptMode,
            options = ScriptMode.entries,
            optionLabel = { it.label },
            onSelect = { onChange(settings.copy(scriptMode = it)) },
        )

        Spacer(Modifier.height(10.dp))
        DropdownField(
            label = "文字排版方向",
            value = settings.textOrientation,
            options = TextOrientation.entries,
            optionLabel = { it.label },
            onSelect = { onChange(settings.copy(textOrientation = it)) },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            settings.textOrientation.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "当前会用的识别器：${ScriptSupport.describe(settings.scriptMode, settings.sourceLanguage)}",
            style = MaterialTheme.typography.bodySmall,
            color = if (unsupported != null) Color(0xFFB3261E) else Color(0xFF2E7D5B),
            fontWeight = FontWeight.Medium,
        )

        if (unsupported != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "⚠ ML Kit 端侧只有拉丁、中文、日文、韩文四套识别器，没有「$unsupported」识别器。" +
                    "所以离线模式和混合模式都读不出这种语言的界面（这不是模型太小，是缺识别器）。" +
                    "请改用「云端 · 整屏多模态」模式，让模型直接看截图。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB3261E),
            )
        } else if (!script.available) {
            Spacer(Modifier.height(6.dp))
            Text(
                "⚠ 当前文字系统没有对应的端侧识别器，离线与混合模式都读不出文字。请改用云端多模态模式。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB3261E),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "看日本漫画：台词是竖排、从右往左的，而 ML Kit 只会横着读 —— 它会把每一「列」" +
                "当成一「行」，同一个对话泡就被拆成好几段分别翻译，读起来语无伦次，而且那种" +
                "又窄又高的框也塞不下横排译文。\n" +
                "「自动」会在发现这种窄长条时，把画面转 90° 再识别一次（列变行、右→左的列序变" +
                "上→下的行序），合并与翻译就都正常了。\n" +
                "如果自动没生效（状态栏那行的识别器后面没有「· 竖排」），手动选「竖排 · 日式」即可；" +
                "反过来，横排内容误选了竖排会一个字都认不出，记得切回「自动」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "为什么老认错字：ML Kit 的识别器是按文字系统分包的。中文识别器认汉字最好，" +
                "顺便也能读拉丁字母；拉丁识别器读英文最准但认不了汉字。所以：\n" +
                "· 界面主要是英文 → 源语言选「英语」（会走拉丁识别器）\n" +
                "· 中英混排 → 用「自动」\n" +
                "· 日文/韩文 → 源语言选对应语言\n" +
                "另外界面文字很小的时候，把「性能与流量」里的「OCR 放大倍数」调到 2 倍会有明显提升。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
// ------------------------------------------------------------------ 性能

@Composable
private fun PerformanceCard(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    SectionCard("性能与流量") {

        // ---- 段落合并：影响译文质量最大的一项，放在最上面 ----
        SwitchRow(
            title = "合并段落换行",
            subtitle = "把被换行拆开的句子合并成一句再翻译（看漫画对话泡必开）",
            checked = settings.mergeParagraphs,
            onCheckedChange = { onChange(settings.copy(mergeParagraphs = it)) },
        )
        if (settings.mergeParagraphs) {
            val presets = Defaults.MERGE_GAP_PRESETS
            val presetIndex = presets
                .indexOfFirst { it >= settings.mergeGapRatio - 0.001f }
                .takeIf { it >= 0 } ?: presets.lastIndex
            LabeledSlider(
                label = "合并强度",
                value = presetIndex.toFloat(),
                range = 0f..presets.lastIndex.toFloat(),
                steps = (presets.size - 2).coerceAtLeast(0),
                valueText = mergeLevelLabel(settings.mergeGapRatio),
                onValueChange = { raw ->
                    val index = raw.roundToInt().coerceIn(0, presets.lastIndex)
                    onChange(settings.copy(mergeGapRatio = presets[index]))
                },
            )
            Text(
                "漫画对话泡、窄栏正文里一句话常常横跨好几行，每行只有几个词。" +
                    "逐行翻译时模型看到的是不完整的短语，翻出来会语无伦次 —— " +
                    "打开这项会先按行距/字号/对齐关系把它们合成一段，再整体翻译。\n" +
                    "发现「本来分开的两句话被合成一句」就调保守；" +
                    "发现「同一句话还是被拆着翻」就调激进。设置页顶部状态栏会显示合并掉了几行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Spacer(Modifier.height(6.dp))

        LabeledSlider(
            label = "OCR 输入宽度上限",
            value = settings.maxCaptureWidth.toFloat(),
            range = 720f..2000f,
            steps = 31,
            valueText = "${settings.maxCaptureWidth} px",
            onValueChange = { onChange(settings.copy(maxCaptureWidth = it.roundToInt())) },
        )
        LabeledSlider(
            label = "OCR 放大倍数",
            value = settings.ocrUpscale,
            range = 1f..3f,
            steps = 3,
            valueText = String.format("%.1f×", settings.ocrUpscale),
            onValueChange = { onChange(settings.copy(ocrUpscale = (it * 2).roundToInt() / 2f)) },
        )
        Text(
            "屏幕上的小字（状态栏、列表副标题、游戏 HUD）往往只有十几像素高，直接识别容易漏字错字。" +
                "放大 1.5~2 倍再识别，中文与日文的准确率提升很明显；代价是每帧多花几十毫秒。" +
                "认不准就调大，嫌慢就调回 1.0×。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            title = "OCR 对比度增强",
            subtitle = "识别前先灰度化并适度拉开对比度，专门救漫画里的彩色文字与花哨背景板。" +
                "纯绘制层实现、几乎不耗时，建议常开；若某个画面反而变差（如网点纸噪声被放大）可关掉。",
            checked = settings.ocrEnhanceContrast,
            onCheckedChange = { onChange(settings.copy(ocrEnhanceContrast = it)) },
        )
        Spacer(Modifier.height(6.dp))
        LabeledSlider(
            label = "单次上报字符上限",
            value = settings.maxCharsPerRequest.toFloat(),
            range = 200f..3000f,
            steps = 27,
            valueText = "${settings.maxCharsPerRequest} 字",
            onValueChange = { onChange(settings.copy(maxCharsPerRequest = it.roundToInt())) },
        )
        LabeledSlider(
            label = "单帧最多翻译行数",
            value = settings.maxBlocksPerRequest.toFloat(),
            range = 5f..80f,
            steps = 14,
            valueText = "${settings.maxBlocksPerRequest} 行",
            onValueChange = { onChange(settings.copy(maxBlocksPerRequest = it.roundToInt())) },
        )
        LabeledSlider(
            label = "最短识别长度",
            value = settings.minTextLength.toFloat(),
            range = 1f..8f,
            steps = 6,
            valueText = "${settings.minTextLength} 字",
            onValueChange = { onChange(settings.copy(minTextLength = it.roundToInt())) },
        )
    }
}

/** 把行间距阈值翻译成人话，用户不需要知道 0.7 是什么单位 */
private fun mergeLevelLabel(ratio: Float): String = when {
    ratio <= 0.55f -> "保守"
    ratio <= 0.65f -> "偏保守"
    ratio <= 0.78f -> "标准（推荐）"
    ratio <= 0.9f -> "偏激进"
    else -> "激进"
}

// ------------------------------------------------------------------ 悬浮球

@Composable
private fun BubbleCard(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val context = LocalContext.current
    SectionCard("悬浮球") {
        SwitchRow(
            title = "显示悬浮球",
            subtitle = "关闭后点击入口只剩通知栏的「翻译一次」按钮，不推荐",
            checked = settings.bubbleEnabled,
            onCheckedChange = { onChange(settings.copy(bubbleEnabled = it)) },
        )
        HorizontalDivider()
        LabeledSlider(
            label = "悬浮球大小",
            value = settings.bubbleSizeDp.toFloat(),
            range = 40f..80f,
            steps = 7,
            valueText = "${settings.bubbleSizeDp} dp",
            onValueChange = { onChange(settings.copy(bubbleSizeDp = it.roundToInt())) },
        )
        LabeledSlider(
            label = "悬浮球不透明度",
            value = settings.bubbleAlpha,
            range = 0.35f..1f,
            steps = 12,
            valueText = String.format("%.2f", settings.bubbleAlpha),
            onValueChange = { onChange(settings.copy(bubbleAlpha = it)) },
        )
        Text(
            "单击悬浮球 = 翻译当前屏幕 · 长按 = 快捷菜单 · 按住拖动 = 换位置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 无条件显示：悬浮球「找不到」的最常见场景（横屏全屏看视频之后）
        // 往往发生在一个我们没预料到的 ROM 行为下，用户得能自救
        TextButton(
            onClick = {
                // 位置有两份记录：绝对像素（旧版）与球心比例（v1.5 起）。
                // 只清像素是没用的 —— 比例存在时 [resolveBubblePosition] 优先用它。
                onChange(
                    settings.copy(
                        bubbleX = -1,
                        bubbleY = -1,
                        bubbleXFrac = -1f,
                        bubbleYFrac = -1f,
                    ),
                )
                // 光改设置不会让已经挂上去的球动，得让服务当场搬一次
                TranslationServiceController.resetBubble(context)
            },
        ) {
            Text("把悬浮球找回来（复位到默认位置）")
        }
        Text(
            "以上改动在服务运行中即时生效，不用重启。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------ 外观

@Composable
private fun AppearanceCard(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    SectionCard("译文外观") {
        SwitchRow(
            title = "显示译文覆盖层",
            subtitle = "关闭后仍会识别与翻译，只是不在屏幕上绘制",
            checked = settings.overlayEnabled,
            onCheckedChange = { onChange(settings.copy(overlayEnabled = it)) },
        )
        HorizontalDivider()

        LabeledSlider(
            label = "字号缩放",
            value = settings.fontSizeScale,
            range = 0.5f..1.8f,
            steps = 12,
            valueText = String.format("%.2fx", settings.fontSizeScale),
            onValueChange = { onChange(settings.copy(fontSizeScale = it)) },
        )
        LabeledSlider(
            label = "底板不透明度",
            value = settings.scrimAlpha,
            range = 0f..1f,
            steps = 19,
            valueText = String.format("%.2f", settings.scrimAlpha),
            onValueChange = { onChange(settings.copy(scrimAlpha = it)) },
        )

        Text(
            "译文颜色",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TEXT_COLOR_PRESETS.forEach { preset ->
                val argb = preset.toInt()
                val selected = settings.textColor == argb
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000),
                            shape = CircleShape,
                        )
                        .clickable { onChange(settings.copy(textColor = argb)) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        OffsetStepper(
            label = "水平对齐微调",
            valueDp = settings.offsetXDp,
            onChange = { onChange(settings.copy(offsetXDp = it)) },
        )
        OffsetStepper(
            label = "垂直对齐微调",
            valueDp = settings.offsetYDp,
            onChange = { onChange(settings.copy(offsetYDp = it)) },
        )
        Text(
            "若译文整体偏移，可在这里以 1dp 步进微调对齐（不同机型状态栏高度差异导致）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OffsetStepper(label: String, valueDp: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onChange(valueDp - 1) }) { Text("−") }
        Text(
            "$valueDp dp",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        TextButton(onClick = { onChange(valueDp + 1) }) { Text("+") }
    }
}

private val TEXT_COLOR_PRESETS = listOf(
    0xFFFFEB3B, 0xFFFFFFFF, 0xFF7CFC9B, 0xFF7FD4FF, 0xFFFFB4AB, 0xFFFFCC80,
)

// ------------------------------------------------------------------ 通用组件

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun <T> DropdownField(
    label: String,
    value: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    optionLabel(value),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}
