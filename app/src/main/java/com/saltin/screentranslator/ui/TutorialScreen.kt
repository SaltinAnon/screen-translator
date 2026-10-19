package com.saltin.screentranslator.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * App 内置使用教程。
 *
 * 为什么值得占一个页面：这个 App 的前置概念确实多（投屏授权、悬浮窗权限、端侧 vs 云端、
 * API Key、模型是否支持看图…），如果只靠设置页里的小字，绝大多数人第一次打开都会卡住。
 * 教程里的内容按「我该做什么」组织，而不是按功能列表组织。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(onBack: () -> Unit) {

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用教程") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeroCard()
            TutorialContent.sections.forEach { section ->
                SectionCard(section)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

// ------------------------------------------------------------------ 顶部卡片

@Composable
private fun HeroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "看懂这一页，这个 App 就完全会用了",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "整个软件只有一件事：把屏幕上的外文，就地换成中文盖在原位置上。" +
                    "而你要做的，永远只是「点一下悬浮球」。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

// ------------------------------------------------------------------ 通用区块

@Composable
private fun SectionCard(section: TutorialSection) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        section.index.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            section.blocks.forEach { block -> BlockView(block) }
        }
    }
}

@Composable
private fun BlockView(block: TutorialBlock) {
    when (block) {
        is TutorialBlock.Paragraph -> Text(
            block.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        is TutorialBlock.Steps -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(22.dp),
                    )
                    Text(item, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        is TutorialBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { item ->
                val parts = item.split("｜", limit = 2)
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "·",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(14.dp),
                    )
                    if (parts.size == 2) {
                        Text(
                            buildString {
                                append(parts[0])
                                append("  ")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(parts[1], style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(item, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        is TutorialBlock.Note -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    when (block.kind) {
                        TutorialNoteKind.INFO -> Color(0xFFEAF1FB)
                        TutorialNoteKind.WARN -> Color(0xFFFFF4E0)
                        TutorialNoteKind.ERROR -> Color(0xFFFDECEA)
                    },
                )
                .padding(12.dp),
        ) {
            Text(
                block.text,
                style = MaterialTheme.typography.bodySmall,
                color = when (block.kind) {
                    TutorialNoteKind.INFO -> Color(0xFF1B4B8A)
                    TutorialNoteKind.WARN -> Color(0xFF8A5B00)
                    TutorialNoteKind.ERROR -> Color(0xFF9B2C1F)
                },
            )
        }

        is TutorialBlock.Rows -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.rows.forEach { (key, value) ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        key,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(96.dp),
                    )
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        is TutorialBlock.Code -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1B1F27))
                .padding(10.dp),
        ) {
            Text(
                block.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFDCE4F0),
            )
        }
    }
}

// ------------------------------------------------------------------ 数据模型

private enum class TutorialNoteKind { INFO, WARN, ERROR }

private sealed interface TutorialBlock {
    data class Paragraph(val text: String) : TutorialBlock
    data class Steps(val items: List<String>) : TutorialBlock
    /** 用 `｜` 分隔「加粗前缀｜正文」 */
    data class Bullets(val items: List<String>) : TutorialBlock
    data class Note(val text: String, val kind: TutorialNoteKind) : TutorialBlock
    data class Rows(val rows: List<Pair<String, String>>) : TutorialBlock
    data class Code(val text: String) : TutorialBlock
}

private data class TutorialSection(
    val index: Int,
    val title: String,
    val blocks: List<TutorialBlock>,
)

// ------------------------------------------------------------------ 教程正文

private object TutorialContent {

    private val p: (String) -> TutorialBlock = { TutorialBlock.Paragraph(it) }
    private val steps: (List<String>) -> TutorialBlock = { TutorialBlock.Steps(it) }
    private val bullets: (List<String>) -> TutorialBlock = { TutorialBlock.Bullets(it) }
    private val rows: (List<Pair<String, String>>) -> TutorialBlock = { TutorialBlock.Rows(it) }
    private val code: (String) -> TutorialBlock = { TutorialBlock.Code(it) }
    private val info: (String) -> TutorialBlock = { TutorialBlock.Note(it, TutorialNoteKind.INFO) }
    private val warn: (String) -> TutorialBlock = { TutorialBlock.Note(it, TutorialNoteKind.WARN) }
    private val danger: (String) -> TutorialBlock = { TutorialBlock.Note(it, TutorialNoteKind.ERROR) }

    val sections: List<TutorialSection> = listOf(

        TutorialSection(
            1,
            "30 秒上手",
            listOf(
                p("第一次用，只要照这三步走。以后再打开就只需要点悬浮球了。"),
                info("设置项按类别分成了五页，用底部的导航栏直接切：\n" +
                    "· 首页｜启动 / 停止、当前状态、权限、教程入口\n" +
                    "· 翻译｜怎么触发翻译、用哪种翻译模式\n" +
                    "· 识别｜源/目标语言、识别器、文字排版方向、OCR 参数（翻得准不准）\n" +
                    "· 模型｜服务商与 API Key、离线模型、接口诊断（用谁翻）\n" +
                    "· 显示｜悬浮球、译文外观、译文框合并（翻出来长什么样）\n" +
                    "不用再一路往下翻找了。"),
                steps(
                    listOf(
                        "到「首页」页的「权限」卡片里，把「显示在其他应用上层」打开。" +
                            "这是悬浮球能出现在别的 App 上面的前提，不开的话你什么也看不到。",
                        "到「模型」页的「模型服务」卡片里选一家服务商、填上 API Key，点「保存并测试」确认通了。" +
                            "不想折腾也可以跳过这步 —— 直接选「端侧离线」模式，不联网也能用（能用的语言有限，见第 6 节）。",
                        "回到「首页」点「开始屏幕翻译」，系统会弹一个投屏授权框，点「立即开始」。",
                    ),
                ),
                p("授权完成后，屏幕右侧会出现一个圆形悬浮球。切到任意一个含外文的 App / 网页，" +
                    "点一下悬浮球，译文就会直接盖在原文上。"),
                info("授权框里的「共享屏幕内容」会把屏幕上的一切都录进来，这是 Android 对投屏类 App 的强制要求，" +
                    "没有别的办法绕过。所有画面都只在本机内存里处理，不上传（云端模式除外，见第 9 节）。"),
            ),
        ),

        TutorialSection(
            2,
            "悬浮球：整个软件的入口",
            listOf(
                p("那个小圆球是唯一需要记住的东西。它的颜色会告诉你当前状态，手势只有三个。"),
                bullets(
                    listOf(
                        "单击｜翻译当前屏幕，译文原地盖在原文上。",
                        "长按｜弹出快捷菜单：翻译一次 / 清空译文 / 开启或关闭连续翻译 / 打开设置 / 停止服务。",
                        "拖动｜按住不放拖到别的位置，位置会被记住，下次启动还在那儿。",
                    ),
                ),
                p("球上的颜色和角标："),
                bullets(
                    listOf(
                        "深蓝 · 译｜待命，等你点它。",
                        "蓝色转圈｜正在识别和翻译。",
                        "绿色 ✓｜翻译完成，右上角角标是这次翻了多少条。",
                        "红色 !｜出错了（没网、Key 不对、模型报错…），屏幕顶部会给出具体原因。",
                    ),
                ),
                warn("如果悬浮球挡住了你的操作，长按它拖到边上即可。也可以到设置里把它整个关掉，" +
                    "之后改用下拉通知栏里的「翻译一次」按钮触发。"),
            ),
        ),

        TutorialSection(
            3,
            "连续翻译：不想每次都点",
            listOf(
                p("默认是「点击翻译」。如果你在看直播字幕、刷长网页、玩外文游戏，可以打开连续翻译，" +
                    "它会按设定间隔一直刷新，不用手点。"),
                bullets(
                    listOf(
                        "怎么开｜长按悬浮球 → 开启连续翻译；或到设置页打开「自动连续翻译」开关。",
                        "取帧间隔｜默认 900 毫秒。调小更跟手但更费电费流量，调大更省但会有滞后感。",
                        "自动清空｜手动模式下可以设置译文在几秒后自动消失，适合不想留着遮挡画面的场景。",
                    ),
                ),
                warn("连续翻译会持续占用 CPU 和网络。长期使用建议接上充电器，或把间隔调到 1500 毫秒以上。"),
            ),
        ),

        TutorialSection(
            4,
            "三种翻译模式，该选哪个",
            listOf(
                rows(
                    listOf(
                        "端侧离线" to "完全在手机上跑，不联网、零流量、不上传任何画面。端侧引擎有三档可选（标准 / 增强 / 全能，见第 6 节），档位越高可直译的方向越多。",
                        "云端·文字为主（推荐）" to "在手机上先把文字识别出来、洗干净，只把这几行文字发给大模型翻译；坐标仍然用手机上识别出的位置，所以覆盖非常准。速度快、流量小、译文质量好。",
                        "云端·整屏多模态" to "把整张屏幕截图交给能看图的模型，让它自己找文字、自己排版。效果最接近人工翻译，但流量和延迟最高，也最贵。",
                    ),
                ),
                p("怎么选："),
                bullets(
                    listOf(
                        "只想看懂大意、又怕隐私｜端侧离线；想要好一点的译文就顺手换掉端侧引擎（第 6 节）。",
                        "日常主力｜云端·文字为主，绝大多数场景都够用。",
                        "排版复杂、字体花哨、普通 OCR 老是认错｜云端·整屏多模态。",
                    ),
                ),
                info("选「云端·文字为主」但用的是纯文本模型（比如 DeepSeek）也完全没问题 —— " +
                    "App 会自动去掉图片那一步，只发文字。"),
            ),
        ),

        TutorialSection(
            5,
            "配置一个云端模型",
            listOf(
                p("App 不绑定任何一家服务商。你选一家、填上 Key，点「保存并测试」，通了就能用。"),
                steps(
                    listOf(
                        "打开服务商官网，注册并创建一个 API Key。",
                        "回到 App 的「模型服务」卡片，在「服务商」下拉里选中对应的一家 —— " +
                            "接口地址和常用模型名是自动填好的，一般不用动。" +
                            "接口地址你从文档里连 /chat/completions 一起粘进来也没关系，会被自动纠正。",
                        "把 API Key 粘贴到输入框，点「保存并测试」。",
                        "再点一次「测试看图」—— 它会现场发一张写着英文单词的图，验证这个模型到底能不能读图。",
                        "看到绿色的「连通正常」「看图正常」就成功了。想换模型直接在「模型名」里改成任意名字，" +
                            "不一定非要用下拉里列的。",
                    ),
                ),
                p("几家常用的、国内可直连的服务商："),
                bullets(
                    listOf(
                        "通义千问｜https://bailian.console.aliyun.com/ ，有免费额度，qwen-vl 系列能看图。注意 qwen3.x 是思考模型，App 已自动替你关掉思考过程，不用管。",
                        "智谱 GLM｜https://bigmodel.cn/usercenter/apikeys ，glm-4v-flash 免费额度很大且能看图，很适合先试。",
                        "DeepSeek｜https://platform.deepseek.com/api_keys ，便宜快、中文好，但不支持看图（会自动走纯文本路径）。",
                        "月之暗面 Kimi｜https://platform.moonshot.cn/console/api-keys ，vision 后缀的模型支持看图。",
                        "硅基流动｜https://cloud.siliconflow.cn/account/ak ，聚合开源模型，注册送额度。",
                        "Google Gemini｜https://aistudio.google.com/apikey ，综合能力强，但需要海外网络环境。",
                    ),
                ),
                p("三个容易踩的坑，App 都替你处理了，但你知道原理会更好排错："),
                bullets(
                    listOf(
                        "思考模型｜Qwen3.x、Gemini 2.5、DeepSeek-R 这类模型会先「想一遍」再回答。思考过程会占掉输出额度，既慢又容易让 JSON 被截断、甚至让正文变成空的。设置页的「关闭思考过程」默认打开；个别模型不允许关闭时会自动去掉该参数重试。",
                        "强制 JSON｜App 会带上 response_format 要求接口返回标准 JSON。少数中转网关不认这个字段会直接报 400，遇到时会自动去掉重试一次，你不用手动处理。",
                        "附加请求参数｜如果你遇到的接口有别的特殊要求，可以在这一栏填任意 JSON（例如 {\"top_k\": 1}），它会合并进请求体。有了它，遇到没见过的接口怪癖不用等新版本。",
                    ),
                ),
                info("想用自己的电脑跑模型？选「本地 Ollama」。注意地址要填电脑在局域网里的 IP（例如 " +
                    "http://192.168.1.100:11434/v1），不是 127.0.0.1；并且要在电脑上设好环境变量 " +
                    "OLLAMA_HOST=0.0.0.0 再重启 Ollama，否则手机连不上。"),
                danger("API Key 会以明文存在本机应用私有目录里，不会上传到任何第三方。" +
                    "建议专门为这个 App 申请一个 Key 并在服务商后台设置消费上限，万一泄漏影响也可控。"),
            ),
        ),

        TutorialSection(
            6,
            "离线模式到底能干什么（重要）",
            listOf(
                p("「端侧离线」模式下，识别和翻译都在手机本地完成，不需要任何 API Key，也不消耗流量。" +
                    "但它的能力边界比大多数人以为的窄，先说清楚，免得你以为软件坏了。"),
                p("限制一：能读哪些文字，取决于 ML Kit 的识别器，而不是模型大小。"),
                bullets(
                    listOf(
                        "能读｜拉丁字母（英文、法文、德文、西班牙文、越南文…）、中文、日文、韩文。",
                        "读不了｜俄语等西里尔文、阿拉伯语、泰语、希伯来文、希腊语、印地语等印度文字。ML Kit 根本没有这些识别器，所以离线模式彻底用不了这些语言的界面。",
                    ),
                ),
                danger("如果你的源语言是上面「读不了」的那些，请把模式改成「云端 · 整屏多模态」，" +
                    "让模型直接看截图。在此之前，无论怎么调参数都只会得到一堆乱码 —— 因为压根没有识别器，不是模型太小。"),
                p("限制二：翻译质量取决于你选了哪一档引擎。端侧有三档可选，在「模型 → 端侧离线引擎」里换。三档按**覆盖面**从窄到宽递进，**每一档都能独立翻译**，越往上可翻的方向越多："),
                bullets(
                    listOf(
                        "标准 · ML Kit（默认）｜每个语言对约 30MB，最快（几十毫秒）。覆盖 59 种语言，但它的模型是围绕英语训练的：非英语互译（如日→中、韩→中）要靠英语中转两次，译文会明显生硬。",
                        "增强 · Opus-MT（直译）｜每个语言对约 115MB（大模型约 270MB），亚秒级。**常见方向直接对译**：" +
                            "中↔英、**中→日**、英↔日/韩/法/德/西/俄…一次到位、**不经英语中转**，质量明显好一档。" +
                            "没有直译模型的方向会自动用英语快速接力，**依然能翻**。",
                        "全能 · Opus-MT 全覆盖｜和「增强」用**同一套**单语言对模型（约 115MB/大模型约 270MB，亚秒级），" +
                            "但**可用方向放到最全**：有直译对就用直译，上游确实没训过的方向（如**日 → 中**）" +
                            "才用英语**两段快速接力**补上（要下两个模型，合计约 0.3 秒，个别句子会带上二次转译的偏差）。" +
                            "**没有 870MB 的大模型**，所以一样快 —— 旧版的 NLLB 档已彻底移除。",
                    ),
                ),
                warn("换成 Opus-MT 之前要先下载模型（在「模型 → 端侧离线引擎」里点「下载模型」）。" +
                    "模型托管在 HuggingFace 上，部分网络下直连不通，所以默认走国内镜像；太慢或失败时可以在同一张卡片里换下载源。" +
                    "下载是**断点续传 + 自动重试**的：网断了、App 被杀掉，重新点一次「继续下载」就从断的地方接着下，已经下好的部分不会白费。"),
                p("怎么用："),
                steps(
                    listOf(
                        "到「模型 → 端侧离线引擎」选一档。选完立刻生效，不用重启。",
                        "点「下载模型」把这一档需要的模型下下来（常规语言对约 115MB；" +
                            "中→日 / 中↔韩用的是本项目自转的大模型，约 270MB；" +
                            "接力方向要下两个包，卡片上会写明）。下载中断不要紧，再点一次会**接着下**。",
                        "回到「识别」页确认源语言与目标语言；Opus-MT 是按语言对训练的，所以必须指定具体源语言，不能用「自动检测」。",
                        "把模式改成「端侧离线」，之后即使开飞行模式也能正常翻译。",
                    ),
                ),
                warn("ML Kit 的模型是「一个语言对一个包」，从英译中换成日译中要再下一个包。" +
                    "Opus-MT 同样按语言对，约 115MB 一个包（直译大模型约 270MB，接力方向两个包），不用了可以单独删除。"),
                info("源语言的自动检测：Opus-MT 不支持（会在设置页提醒你指定具体语言），" +
                    "ML Kit 则一律按英语处理并给出提醒。"),
                info("上面这些提醒（接力、源语言兜底…）在屏幕顶部只会出现一次，" +
                    "停留几秒后自动收起，不会每翻一帧就弹出来挡住画面。想看的时候回到本节即可。"),
                info("一句话总结：只是想看懂英文界面 → 用默认的 ML Kit；常翻英中互译、想要好一档的译文 → 换「增强 · Opus-MT」；" +
                    "要看日文漫画、要方向最全 → 换「全能 · Opus-MT 全覆盖」（缺直译对时自动英语接力）。" +
                    "都不装也可以，用「云端 · 文字为主」同样很快很省。"),
            ),
        ),

        TutorialSection(
            7,
            "漫画 / 小说：为什么要把整句话一起翻",
            listOf(
                p("这是最容易被忽略、但对效果影响最大的一环。"),
                p("OCR 识别出来的最小单位是「屏幕上的第几行」。可是现实里的文字是被换行拆开的 —— " +
                    "漫画的对话泡、窄栏小说、手机上的多行说明，一句话往往横跨三五行，每行只有几个词。"),
                p("如果拿每一行单独去翻译，模型看到的是「不完整的短语」，只能猜。结果就是每句单看都通顺、" +
                    "连起来语无伦次："),
                code(
                    "对话泡原文（一个泡子里的三行）\n" +
                        "  I don't know what\n" +
                        "  you mean by that,\n" +
                        "  but I'll try.\n\n" +
                        "逐行翻译（v1.2 之前的行为）\n" +
                        "  我不知道什么\n" +
                        "  你的意思是这样，\n" +
                        "  但我会试试。\n\n" +
                        "合并成一段再翻译（现在的默认行为）\n" +
                        "  我不明白你的意思，但我会试试。",
                ),
                p("「性能与流量」卡片里最上面那个「合并段落换行」开关就是干这个的，" +
                    "默认打开，你什么都不用做。它是怎么判断哪几行属于同一段的："),
                bullets(
                    listOf(
                        "行间距｜换行的行距很小（通常不到半个字高），而 UI 列表里两条之间的留白要大得多。这是最有效的一条判据。",
                        "字号｜标题和正文的字号差得很明显，不会被当成同一段。",
                        "对齐边｜同一段里的几行一定会共享一条对齐边（左边齐 / 居中 / 右边齐），合并时也会记下来，译文折行时照同样的方式排。",
                        "句末标点｜上一行以句号结尾只是「减分」，不会硬拆 —— 因为一个对话泡里本来就常有好几句话，把整个泡子一起翻才是你要的。",
                    ),
                ),
                p("合并强度怎么调："),
                bullets(
                    listOf(
                        "标准（推荐）｜默认档位，绝大多数漫画和正文都合适。",
                        "偏保守 / 保守｜发现本来分开的两句话被合成了一句，就往下调。",
                        "偏激进 / 激进｜发现同一句话还是被拆着翻（多见于排版很松的漫画），就往上调。",
                    ),
                ),
                info("设置页顶部的状态栏会显示「识别 N 行 → 保留 M 段（合并掉 X 行）」，" +
                    "可以据此判断合并到底有没有生效。完全是正常界面（比如社交 App 的列表）时，" +
                    "合并数通常是 0，这也是对的。"),
                warn("如果你看到译文「粘在一起、位置也变成了整段」，说明合并生效了。" +
                    "不喜欢这种感觉（比如你更想逐行对照），把开关关掉即可退回 v1.2 的逐行行为。"),

                p("竖排的日本漫画要额外多一步。"),
                p("日本漫画的台词是竖排、从右往左写的，而 ML Kit 的识别器是为横排设计的 —— " +
                    "它会把每一「列」当成一「行」交出来。于是同一个对话泡被拆成好几段分别翻译，" +
                    "读起来语无伦次；而且每一列的框又窄又高，横排译文塞进去只剩一条细线。"),
                code(
                    "竖排原文（3 列，从右往左读）      直接按横排识别的结果\n" +
                        "  こ ん な                       →  「こんな」\n" +
                        "  ん な と                          「なこと」\n" +
                        "  な と こ                          「こと、」\n" +
                        "  と こ 、\n\n" +
                        "                    ↓ 先把画面转 90° 再识别（列变行、右→左变上→下）\n\n" +
                        "  こんな / なこと / こと、  →  合并成一段  →  一句连贯的译文",
                ),
                p("「语言与识别」卡片里的「文字排版方向」就是干这个的，默认「自动」："),
                bullets(
                    listOf(
                        "自动（推荐）｜先按横排识别，如果结果里三分之二以上都是「又窄又高的长条」，就判定这是竖排，自动转 90° 重识别一次并择优。横排画面完全不受影响。",
                        "横排｜只按横排识别。普通界面、横排漫画用这个。",
                        "竖排 · 日式（右→左）｜强制转 90°。自动没生效时手动选它。反过来，横排内容误选了竖排会一个字都认不出。",
                    ),
                ),
                info("怎么确认生效了：状态栏那行「识别器」后面会多出「· 竖排」三个字。" +
                    "另外竖排识别需要多跑一次 OCR（约几十毫秒），只在确实像竖排时才会触发。"),

                p("收尾：一个对话泡只留一个翻译框。"),
                p("就算段落合并生效了，也总有漏网的 —— 竖排列与列之间排得比正常换行松，可能没被合并；" +
                    "而且为了让横排译文塞得进那种又窄又高的列框，App 会把框横向撑开，撑开之后相邻的框直接重叠。" +
                    "观感就是「竖排翻译后一堆框互相挡着，把漫画本身都盖住了」。"),
                p("所以翻译完成之后还有一道收口：把紧挨着的译文框合成一个 —— 包围盒取并集" +
                    "（也就是那个对话泡本身），译文按阅读顺序拼成一段（竖排按从右往左的列序），再整体折行排版。" +
                    "它对端侧、混合、多模态三种模式一视同仁，所以是「不管怎么翻，一个泡子只有一个框」。"),
                bullets(
                    listOf(
                        "自动 · 只合并对话泡（推荐）｜只有版面看起来是竖排（超过一半的框又窄又高）时才动手。普通界面、网页、设置列表保持逐行覆盖，不受影响。",
                        "总是合并相邻文本｜无条件把紧挨着的译文框合成一个。看横排漫画、外语字幕截图时最干净，但用在设置页/网页上会把整屏文字糊成一大块。",
                        "关闭｜退回 v1.4.0 的行为：OCR 给几块就画几块。",
                    ),
                ),
                info("位置在「显示」页最下面的「译文框合并」卡片，下面还有一个「合并强度」滑杆。" +
                    "状态栏会显示「泡子合并掉 N 个框」，可以据此确认它到底有没有生效。"),
            ),
        ),

        TutorialSection(
            8,
            "调参：让译文更准更舒服",
            listOf(
                p("这些参数都有合理默认值，先按默认用，遇到具体问题再回来改。"),
                rows(
                    listOf(
                        "合并段落换行" to "把被换行拆开的句子合成一句再翻译。看漫画、小说、长段正文时必开（默认已开）。详见第 7 节。",
                        "译文框合并（对话泡）" to "翻译完成后，把同一个对话泡里紧挨着的多块译文合成一个框，不再互相遮挡。竖排漫画必开（默认「自动」，只在竖排版面生效）。详见第 7 节。",
                        "译文框合并强度" to "两块译文之间隔多远还算同一个泡子。不同泡子被粘成一个框就调保守，同一泡子还是好几块就调激进。",
                        "文字系统" to "决定用哪套 OCR 识别器。ML Kit 只有拉丁、中文、日文、韩文四套。卡片下方会实时告诉你「当前会用的识别器」，选错了它认出来的就是乱码。",
                        "文字排版方向" to "横排还是竖排（日式漫画的台词是竖排、从右往左）。默认「自动」会在发现竖排时把画面转 90° 重识别。详见第 7 节。",
                        "OCR 放大倍数" to "识别前把画面放大几倍。屏幕上的小字（状态栏、列表副标题、游戏 HUD）直接识别容易漏字错字，放大 1.5~2 倍对中文日文提升很明显。默认 1.5×；嫌慢就调回 1.0×。",
                        "OCR 输入宽度上限" to "放大之前的基准宽度。调大能保住更多细节，调小更快更省电。默认 1440，尽量不小于屏幕宽度。",
                        "单帧最多翻译行数" to "一屏文字太多时，只翻译前 N 行。调大能翻更多，但一帧的耗时和花费都会上升，也更容易让模型输出被截断。",
                        "单次上报字符上限" to "一次最多发多少字符给云端，是控成本的主要旋钮。",
                        "关闭思考过程" to "思考模型会先想再答，慢且容易把 JSON 撑断。默认开启关闭。",
                        "字号缩放" to "译文相对原文框的大小。如果译文被压在框里显得太小，调大它。",
                        "底板不透明度" to "译文背后那层半透明色块。调高能把原文压得更暗、译文更清晰；调低则更接近「直接盖一层字」的效果。",
                    ),
                ),
                p("如果整体译文位置有偏移（多见于带挖孔屏、曲面屏的机型），用「水平/垂直对齐微调」按 dp 逐步补偿，一边调一边看效果即可。"),
            ),
        ),

        TutorialSection(
            9,
            "隐私：屏幕内容去了哪里",
            listOf(
                bullets(
                    listOf(
                        "端侧离线｜所有画面和文字都在手机内存里处理完即丢，不产生任何网络请求。",
                        "云端模式｜只有「这一帧」的数据会被发给你自己配置的那家服务商 —— 混合模式发文字，多模态模式发截图。App 本身没有任何服务器，不收集也不上传任何数据。",
                        "译文缓存｜同一个句子的译文会缓存在内存里（LRU，约 512 条），用于省流量，进程退出即清空。",
                        "API Key｜存在应用私有目录的 SharedPreferences 里，只有本 App 能读。",
                    ),
                ),
                warn("如果你在屏幕上看的是银行卡、身份证、聊天记录之类的内容，请用端侧离线模式，" +
                    "或者干脆先关掉翻译。"),
                info("DRM 保护的视频（Netflix 等）在投屏下会显示为黑屏，这是系统限制，任何录屏/翻译类 App 都绕不过去。"),
            ),
        ),

        TutorialSection(
            10,
            "遇到问题怎么办",
            listOf(
                p("先看屏幕顶部的提示条 —— 几乎所有问题它都会直接说清楚。提示条分两档：" +
                    "红色是致命错误（会一直留着，直到问题解决），黄色是提醒（看几秒后自动收起，" +
                    "同一条提醒每次开启翻译只出现一次，不会反复弹出来挡画面）。下面是常见几种："),
                rows(
                    listOf(
                        "看不到悬浮球" to "九成是没给「显示在其他应用上层」权限。到「首页」页的「权限」卡片点「去授权」，在系统列表里找到本 App 打开开关，然后重新点「开始屏幕翻译」。如果权限已给、球还是不见（比如全屏看视频转横屏之后），到「显示」页的「悬浮球」卡片点「把悬浮球找回来」—— 它会立刻把球搬回默认位置。",
                        "球在，但点了没反应" to "看球变成红色 ! 还是蓝色转圈超过 3 秒。红色看顶部提示条；一直转圈多半是网络不通。",
                        "提示「没有识别到文字」" to "确认屏幕上确实有外文；把「OCR 放大倍数」调到 2×；再检查「识别」页里显示的「当前会用的识别器」对不对（认日文却选了中文识别器，自然认不出来）。",
                        "识别出来全是乱码" to "典型原因是识别器选错了，或者源语言的文字系统端侧根本读不了。看「识别」页那行「当前会用的识别器」：如果显示「没有可用识别器」，就必须改用云端多模态模式。",
                        "译文一句一句不连贯" to "典型是没有把同一段的换行合并起来，被逐行翻译了。到「识别」页的「性能与流量」卡片确认「合并段落换行」已打开，并把「合并强度」调到偏激进。详见第 7 节。",
                        "本来分开的两句话被合成一句" to "合并过头了。把「合并强度」往下调一档（偏保守或保守）；如果是个别 App 的界面本来就不该合并，直接关掉「合并段落换行」。",
                        "竖排日文漫画还是被拆着翻" to "日本的漫画台词是竖排、从右往左的。到「识别」页把「文字排版方向」手动选成「竖排 · 日式」；正常生效时状态栏那行的识别器后面会显示「· 竖排」。",
                        "竖排翻译后还是好几个框互相遮挡" to "这是框没被合并。到「显示」页的「译文框合并」卡片确认是「自动」或「总是合并」（默认「自动」，只在竖排版面生效），然后把「合并强度」调大一两档 —— 有些漫画的列排得很松，标准档位够不着。状态栏的「泡子合并掉 N 个框」能确认有没有生效。",
                        "整屏文字被糊成一大块" to "「译文框合并」选到了「总是合并」，它会把普通界面的每一行也粘起来。切回「自动」，或直接把这项关掉，就恢复逐行原位覆盖。",
                        "多模态模式下提示「没有可用的译文块」" to "整屏多模态要求模型既看懂图、又按坐标回话，而不少看图模型（Qwen-VL 等）只肯回译文。v1.4 起遇到这种情况会自动改用「端侧 OCR 找框 + 让模型填译文」再问一次；如果仍然失败，说明这个模型连看图都不稳，换一个带 vision / vl 的模型即可。",
                        "提示「没有可用的译文块」" to "模型返回了内容但格式不对。请打开「模型」页最下方的「接口诊断」，把里面的内容复制出来 —— 那里面就有模型的原始返回，一看便知是接口不认参数还是模型没按格式回。",
                        "提示模型正文是空的" to "这是思考模型没关掉思考过程的典型症状。到「模型」页的「模型服务」卡片确认「关闭思考过程」已打开；如果确实关掉了还报这个，多半是该服务商要求流式调用，或额度已用完。",
                        "译文和原文一模一样" to "离线模式下说明这一对的模型没准备好：ML Kit 要先去「离线翻译」卡片下载；Opus-MT 要先去「端侧离线引擎」下载模型，或者当前方向该引擎确实翻不了（Opus-MT 是靠英语接力的，如果连某一侧的英语方向上游都没训过，就真的翻不了）。屏幕顶部的提示条会直接告诉你原因。用「云端 · 文字为主」最省事。",
                        "下载模型时提示「不完整 / 下载出错」" to "v1.5 及以前拿**估算的字节数**当校验基准，导致每个文件下完都被判成不完整（Opus 编码器真实 52899742 字节，被 52844544 的估算值判错）。v1.5.1 起改成以服务端返回的真实大小为准，并且每个文件自带最多 5 次重试与断点续传。如果升级后仍然失败，多半是下载源不通，在同一张卡片里换一个源再试。",
                        "横屏（比如全屏看视频）时悬浮球不见了" to "v1.5.1 修复。之前悬浮球的位置只在「点一下球」时才重新校正，而球跑到屏幕外之后根本点不到 —— 手动模式下就成了死锁。现在有一条每秒一次的守护通路，会无条件把球夹回屏幕内，并且在「设置 → 显示 → 悬浮球」里留了「把悬浮球找回来」按钮兜底。",
                        "译文位置整体偏移" to "到「显示」页的「译文外观」卡片，用「水平/垂直对齐微调」补偿。不同机型的顶部状态栏和挖孔高度差异很大，这属于正常现象。",
                        "提示 401 / 403" to "API Key 不对，或者这个 Key 没有所选模型的权限（常见于要单独开通的模型）。重新复制一次 Key，或换一个模型名。",
                        "提示 404" to "接口地址或模型名写错了。把服务商切回去一次可以恢复默认地址。",
                        "提示 429" to "触发限流或免费额度用完了。换一家服务商，或把「取帧间隔」「单帧最多翻译行数」调小。",
                        "提示 402 / 说「请求被服务商拒绝」" to "国内不少服务商拿 402 表示**内容安全拦截**，而不是欠费：发出去的截图（混合模式的辅助截图、或整屏多模态的整屏图）被判定为敏感内容时，整个请求会被拒掉。v1.6.3 起遇到这种情况会自动丢掉图片、改用**纯文字**重翻同一批文字，屏幕上照样有译文，只是少了图片语境；整屏多模态被拒时也会自动退回「OCR + 纯文本」。想彻底不再尝试带图，到「模型」页关掉「混合模式下附带截图」。",
                        "翻译到一半自己停了" to "多半是你在系统里撤销了投屏，或者系统回收了后台服务。重新点「开始屏幕翻译」即可。",
                    ),
                ),
                info("Android 14 及以上，每次启动服务都必须重新确认一次投屏授权 —— 这是系统行为，" +
                    "不是 App 的 bug，暂时没有绕过的办法。"),
            ),
        ),

        TutorialSection(
            11,
            "关于磁贴和通知",
            listOf(
                p("除了悬浮球，还有两个入口可以控制服务："),
                bullets(
                    listOf(
                        "快捷设置磁贴｜下拉通知栏点右上角的编辑按钮，把「屏幕翻译」拖到快捷面板里。磁贴是开关语义：点亮就是启动服务（会弹投屏授权），再点一次就是停止。",
                        "常驻通知｜服务运行时通知栏会有一条常驻通知，上面有「翻译一次」「清空」「停止」三个按钮。悬浮球被别的东西挡住时，用它最方便。",
                    ),
                ),
                info("磁贴本身弹不出投屏授权框（系统限制），所以点它之后会闪一下再弹出授权对话框，" +
                    "那个一闪而过的透明页面是正常的。"),
            ),
        ),

        TutorialSection(
            12,
            "接口诊断：出问题时该提供什么",
            listOf(
                p("「模型」页最下方有一张「接口诊断」卡片。它会保存最近一次云端请求和模型的原始返回" +
                    "（图片数据已省略、API Key 已抹掉，可以放心贴出去）。"),
                steps(
                    listOf(
                        "复现一次问题（点悬浮球翻译一次，或点「保存并测试」）。",
                        "到「模型」页最下方，点「查看最近一次交互」。",
                        "把看到的内容整段复制。里面能直接看出：接口返回了什么、模型到底输出了什么。",
                    ),
                ),
                bullets(
                    listOf(
                        "日志里能看到什么｜请求用的地址与模型名、HTTP 状态码、完整的请求体（图片变成「已省略约 XXKB」）、模型的原始返回文本。",
                        "为什么有用｜比如模型其实返回了 {\"blocks\":[...]} 但键名不是 i，或者返回变成了 markdown 代码块，或者 HTTP 400 说某个参数不认识 —— 这些在界面上只显示一句「失败」，但在诊断里一目了然。",
                        "注意｜里面可能有你在屏幕上看到的内容片段。分享给别人前自己扫一眼。",
                    ),
                ),
                info("同一张卡片上还有一个「清除」按钮。看完了点一下，避免残留。" +
                    "诊断信息只存在内存里，App 一退出就没了，不会被写入磁盘。"),
            ),
        ),
    )
}
