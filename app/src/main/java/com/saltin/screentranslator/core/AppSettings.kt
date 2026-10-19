package com.saltin.screentranslator.core

import com.saltin.screentranslator.ocr.ScriptMode
import com.saltin.screentranslator.ocr.TextOrientation
import com.saltin.screentranslator.translate.offline.OfflineEngineKind

/** 全局默认值 / 常量 */
object Defaults {
    const val LLM_PROVIDER = "gemini"
    const val LLM_MODEL = "gemini-2.5-flash"
    const val CAPTURE_INTERVAL_MS = 900L
    const val MAX_CAPTURE_WIDTH = 1440
    const val MAX_CHARS_PER_REQUEST = 1400
    const val MAX_BLOCKS_PER_REQUEST = 40
    const val ON_DEVICE_FALLBACK_SOURCE = "en"
    const val UPLOAD_MAX_SIDE = 1024
    const val JPEG_QUALITY = 72
    const val BUBBLE_SIZE_DP = 56
    const val BUBBLE_ALPHA = 0.92f

    /** OCR 输入放大倍数：小字识别的关键开关，1.5 倍对中文/日文界面提升明显 */
    const val OCR_UPSCALE = 1.5f

    /**
     * OCR 前是否做「灰度 + 温和对比度拉伸」，默认开。
     *
     * 对漫画里常见的彩色文字、花哨背景板有明确帮助，而清晰的黑白正文几乎无差别
     * （灰度化对 ML Kit 本身零损失，它内部也是按灰度处理的）。成本极低，所以默认开。
     */
    const val OCR_ENHANCE_CONTRAST = true

    /**
     * 段落合并的行间距上限（相对行高）。
     *
     * 0.7 是实测的甜点位：正常换行的行距在 0.3~0.6 倍行高之间，
     * 而 UI 列表项之间的留白通常 ≥ 0.8 倍 —— 正好卡在中间。
     */
    const val MERGE_GAP_RATIO = 0.7f

    /** 「合并段落」滑杆的可选档位，避免用户拖出无意义的小数值 */
    val MERGE_GAP_PRESETS = listOf(0.5f, 0.6f, 0.7f, 0.85f, 0.95f)

    /**
     * 译文框合并的间距上限（相对框的厚度）。
     *
     * 这里的单位跟 [MERGE_GAP_RATIO] 不一样：那边量的是「行间距 ÷ 行高」，
     * 这边量的是「两个译文框之间的空隙 ÷ 框的厚度」，而且横竖两个方向都量。
     * 漫画对话泡内部的行距/列距通常在这个值以下，泡子与泡子之间的留白远大于它。
     * 0.85 是实测甜点位：竖排日文那种排得比较松的列也能合上，相邻 UI 条目又不会被粘起来。
     */
    const val BUBBLE_GAP_RATIO = 0.85f

    /** 「译文框合并」滑杆的可选档位 */
    val BUBBLE_GAP_PRESETS = listOf(0.6f, 0.75f, 0.85f, 1.0f, 1.15f, 1.35f, 1.6f)
}

/**
 * 译文框合并策略：把「一个对话泡里的好几块译文」合成**一个框**再绘制。
 *
 * ## 为什么需要它
 *
 * 原文的一句话说在同一个对话泡里，但 OCR 是按行（竖排时是按列）交出来的，
 * 于是同一个泡子会拿到好几个框，翻译完就画成好几块，互相压在一起挡住漫画画面。
 * 竖排日文尤其明显：每一列都是又窄又高的框，为了塞下横排译文还得先横向撑开，
 * 撑开之后相邻列直接重叠 —— 观感就是「一泡子糊成一片」。
 *
 * 这一步放在**翻译之后**，作用在最终屏幕坐标上，所以不管前面走的是
 * 端侧、混合还是多模态、有没有段落合并，产出的框都会在这里被归并一次。
 *
 * [AUTO] 的判断很保守：只有版面看起来像「竖排」时才动手（见 `BubbleMerger.looksColumnar`），
 * 普通 App 界面、网页、设置列表都保持逐行原位覆盖，不会被糊成一块。
 */
enum class BubbleMergeMode(val id: String, val label: String, val detail: String) {
    OFF(
        "off",
        "关闭（每行一个框）",
        "v1.4.0 的行为：OCR 给几块就画几块。忠实反映原文版式，但竖排漫画里同一泡子会有好几个框互相遮挡。",
    ),
    AUTO(
        "auto",
        "自动 · 只合并对话泡（推荐）",
        "只有当版面看起来是「竖排」（超过一半的框又窄又高）时，才把同一个泡子里的相邻框合成一个。" +
            "普通界面、网页、设置列表保持逐行显示，不受影响。",
    ),
    ALWAYS(
        "always",
        "总是合并相邻文本",
        "无条件把紧挨着的译文框合成一个。看横排漫画、外语字幕截图时最干净，" +
            "但用在App设置页/网页上会把整屏文字糊成一大块，那种场景请用「自动」。",
    ),
    ;

    companion object {
        fun fromId(id: String?): BubbleMergeMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * 阶段三的三种工作模式。
 *
 * [ON_DEVICE]     只走端侧：ML Kit OCR + ML Kit 离线翻译，全程离线（需先下载约 30MB 小模型）。
 * [HYBRID]        混合架构（推荐）：端侧 OCR + 文本清洗，只把「干净的短文本」（可选再加一张缩略图）
 *                 交给云端大模型，坐标直接复用 OCR 结果，避免模型编造坐标。
 * [CLOUD_VISION]  纯多模态：整帧交给云端看图的模型，由模型直接返回译文与归一化坐标。
 */
enum class TranslateMode(val id: String, val label: String, val shortLabel: String, val description: String) {
    ON_DEVICE(
        "on_device",
        "端侧离线（不联网）",
        "离线",
        "ML Kit OCR + 离线翻译，零流量、零上传，适合隐私敏感与弱网场景。" +
            "首次使用某个语言对需要下载约 30MB 的本地小模型，译文是逐句直译，语境能力一般。",
    ),
    HYBRID(
        "hybrid",
        "云端 · 文字为主（推荐）",
        "混合",
        "端侧 OCR 清洗后，只把干净短文本（可再带一张缩小截图）交给云端大模型，坐标复用 OCR 结果。" +
            "延迟低、流量省、译文质量好，且不支持看图的模型（如 DeepSeek）也能用。",
    ),
    CLOUD_VISION(
        "cloud_vision",
        "云端 · 整屏多模态",
        "多模态",
        "整帧交给云端看图的模型，由模型自行定位并翻译，排版还原与语境最好。" +
            "流量与延迟最高，且必须使用支持看图的模型。",
    ),
    ;

    companion object {
        fun fromId(id: String?): TranslateMode = entries.firstOrNull { it.id == id } ?: HYBRID
    }
}

/** 可选语言（云端与端侧取交集，端侧不支持 auto） */
object SupportedLanguages {
    val entries: List<Pair<String, String>> = listOf(
        "auto" to "自动检测",
        "en" to "英语",
        "zh" to "中文（简体）",
        "zh-Hant" to "中文（繁体）",
        "ja" to "日语",
        "ko" to "韩语",
        "fr" to "法语",
        "de" to "德语",
        "es" to "西班牙语",
        "pt" to "葡萄牙语",
        "ru" to "俄语",
        "it" to "意大利语",
        "ar" to "阿拉伯语",
        "th" to "泰语",
        "vi" to "越南语",
        "id" to "印尼语",
        "hi" to "印地语",
        "tr" to "土耳其语",
        "nl" to "荷兰语",
        "pl" to "波兰语",
    )

    /**
     * ML Kit 端侧**翻译**支持的语言（与 Google 翻译离线模式同一批模型，共 59 种）。
     *
     * ⚠️ 注意：这是翻译模型的支持范围，跟 **OCR 识别** 的支持范围是两件事。
     * 端侧 OCR 只有拉丁 / 中文 / 日文 / 韩文四套识别器，
     * 详见 `ocr/ScriptSupport.kt`。把中文译成俄语没问题（识别中文、输出俄语），
     * 但想读俄语界面就必须走云端多模态。
     */
    val mlKitSupported: Set<String> = setOf(
        "af", "sq", "ar", "be", "bn", "bg", "ca", "zh", "hr", "cs",
        "da", "nl", "en", "eo", "et", "fi", "fr", "gl", "ka", "de",
        "el", "gu", "ht", "he", "hi", "hu", "is", "id", "ga", "it",
        "ja", "kn", "ko", "lv", "lt", "mk", "ms", "mt", "mr", "no",
        "fa", "pl", "pt", "ro", "ru", "sk", "sl", "es", "sw", "sv",
        "tl", "ta", "te", "th", "tr", "uk", "ur", "vi", "cy",
    )

    fun label(code: String): String = entries.firstOrNull { it.first == code }?.second ?: code

    /** ML Kit 端侧翻译只认基础语言码 */
    fun toMlKitCode(code: String): String = when (code.lowercase()) {
        "auto" -> Defaults.ON_DEVICE_FALLBACK_SOURCE
        "zh-hant", "zh-tw", "zh-hk", "zh-hans", "zh-cn" -> "zh"
        else -> code.substringBefore('-').lowercase()
    }

    fun onDeviceSupported(source: String, target: String): Boolean =
        toMlKitCode(source) in mlKitSupported && toMlKitCode(target) in mlKitSupported

    /**
     * ML Kit 的翻译模型是**围绕英语**训练的：英语↔其他语言是直接模型，
     * 非英语之间（如日→中、韩→中）会用英语做中间语言中转，质量会明显下降。
     * 返回一句可以直接展示给用户的提醒，英语参与的语对返回 null。
     */
    fun onDeviceQualityWarning(source: String, target: String): String? {
        val src = toMlKitCode(source)
        val tgt = toMlKitCode(target)
        if (src == tgt) return null
        if (src == "en" || tgt == "en") return null
        return "端侧离线模型只在「英语 ↔ 其他语言」之间直接训练过，" +
            "「${label(source)} → ${label(target)}」会用英语中转，译文质量天然有限。" +
            "想要好结果请改用云端模式（可关掉「附带截图」省流量，纯文字翻译也很便宜）。"
    }
}

/**
 * 全部可调参数。通过 [SettingsStore] 持久化，前台服务每帧读取一次，改完即时生效。
 */
data class AppSettings(
    val mode: TranslateMode = TranslateMode.HYBRID,

    /**
     * 端侧离线用哪一档引擎（只在 [TranslateMode.ON_DEVICE] 下生效）。
     *
     * 默认仍是 ML Kit：它的体积最小、速度最快，而且是唯一「按语言对自动下载、
     * 用户零配置」的一档。想要单语言对直译、质量更高一档就换 Opus-MT ——
     * 后者要在「模型」页手动下载模型，详见 [OfflineEngineKind]。
     */
    val offlineEngine: OfflineEngineKind = OfflineEngineKind.ML_KIT,

    /**
     * 增强离线模型的下载源。
     *
     * 留空 = 用内置的 `hf-mirror.com`（HuggingFace 国内镜像）。官方源 `huggingface.co`
     * 在部分网络下完全不可达，所以默认不走它；自建镜像填主机名即可（如 `hf.example.com`）。
     */
    val offlineModelHost: String = "",

    val sourceLanguage: String = "en",
    val targetLanguage: String = "zh",
    val scriptMode: ScriptMode = ScriptMode.AUTO,

    /**
     * 文字排版方向。
     *
     * 日本漫画的台词是**竖排、从右往左**的，而 ML Kit 的识别器是为横排设计的，
     * 会把每一「列」当成一「行」交出来 —— 结果就是同一个对话泡被拆成好几段分别翻译，
     * 而且每列的框又窄又高，横排译文塞进去只剩一条细线。
     *
     * 选「自动」时：先按横排识别，如果结果看起来是「竖排被横着切」，就自动把画面
     * 转 90° 重识别一次并择优（详见 `ocr/TextOrientation.kt`）。
     */
    val textOrientation: TextOrientation = TextOrientation.AUTO,

    // ---------------------------------------------------------- 触发方式

    /**
     * 关闭（默认）= 点击翻译：悬浮球点一下才抓一帧翻译当前页面；
     * 打开 = 自动连续翻译：按 [captureIntervalMs] 持续刷新。
     */
    val autoTranslate: Boolean = false,

    /** 手动模式下译文保留多少秒后自动消失；0 = 一直保留，直到用户双击悬浮球清空 */
    val autoClearSeconds: Int = 0,

    // ---------------------------------------------------------- 性能

    val captureIntervalMs: Long = Defaults.CAPTURE_INTERVAL_MS,
    val maxCaptureWidth: Int = Defaults.MAX_CAPTURE_WIDTH,

    /**
     * OCR 输入放大倍数。
     *
     * 屏幕上的小字（状态栏、列表副标题、游戏 HUD）往往只有十几像素高，
     * 直接识别容易漏字/错字。放大 1.5~2 倍再送识别，中文与日文的准确率提升尤其明显。
     * 代价是耗时与内存（内部有约 600 万像素的硬上限）。设成 1.0 可以换回速度。
     */
    val ocrUpscale: Float = Defaults.OCR_UPSCALE,

    /**
     * OCR 前是否做「灰度 + 温和对比度拉伸」。
     *
     * 针对漫画里常见的**彩色文字**与**花哨背景板**：ML Kit 的识别器主要以高对比度
     * 黑白正文训练，低对比度彩色字很容易整行漏掉。灰度化把「同亮度不同色相」的组合
     * 归到一起，再由对比度拉伸把濒临淹没的字重新拉开。
     *
     * 纯 Canvas 层实现，不读像素，耗时几十微秒，可以放心常开；
     * 万一某个画面反而变差（比如网点纸噪声被放大），关掉即可回到原行为。
     */
    val ocrEnhanceContrast: Boolean = Defaults.OCR_ENHANCE_CONTRAST,

    val minTextLength: Int = 2,
    val maxCharsPerRequest: Int = Defaults.MAX_CHARS_PER_REQUEST,
    val maxBlocksPerRequest: Int = Defaults.MAX_BLOCKS_PER_REQUEST,

    // ---------------------------------------------------------- 段落合并

    /**
     * 是否把「同一段里被换行拆开的行」合并成一句再翻译。
     *
     * 漫画对话泡、窄栏正文里一句话会横跨好几行，每行只有几个词。
     * 逐行翻译时模型看到的是不完整的短语，只能瞎猜，译文会语无伦次。
     * 打开后按行距/字号/对齐关系先合并成段，再整体翻译。
     */
    val mergeParagraphs: Boolean = true,

    /**
     * 合并的激进程度：行间距上限（相对行高）。
     *
     * 越大越容易把相邻的两行判成同一段。
     * 如果发现「本来分开的两句话被合成了一句」，把它调小；
     * 如果发现「同一句话还是被拆着翻」，就调大。见 [Defaults.MERGE_GAP_PRESETS]。
     */
    val mergeGapRatio: Float = Defaults.MERGE_GAP_RATIO,

    // ---------------------------------------------------------- 译文框合并（对话泡）

    /**
     * 是否把「同一个对话泡里的多块译文」合成一个框。
     *
     * 这一步在翻译之后、渲染之前执行，作用在最终屏幕坐标上，所以对三种模式一视同仁。
     * 详见 [BubbleMergeMode]。
     */
    val bubbleMerge: BubbleMergeMode = BubbleMergeMode.AUTO,

    /**
     * 合并的激进程度：两个框之间的空隙超过「框厚度 × 这个值」就不合并。
     * 越大越容易合并。发现「不同泡子被粘成一个框」就调小，发现「同一泡子还是好几块」就调大。
     * 见 [Defaults.BUBBLE_GAP_PRESETS]。
     */
    val bubbleGapRatio: Float = Defaults.BUBBLE_GAP_RATIO,

    // ---------------------------------------------------------- 悬浮球 / 渲染

    val bubbleEnabled: Boolean = true,
    val bubbleSizeDp: Int = Defaults.BUBBLE_SIZE_DP,
    val bubbleAlpha: Float = Defaults.BUBBLE_ALPHA,

    /**
     * 悬浮球位置（像素，相对屏幕左上角）；-1 表示还没拖动过，用默认位置。
     *
     * ⚠️ 这是**绝对像素**，只有在「屏幕跟上次一样」的前提下才有意义。
     * 真正决定位置的是下面那对比例值 —— 屏幕一旋转，原来的 (x, y)
     * 拿到新坐标系里多半已经跑到屏幕外了。
     */
    val bubbleX: Int = -1,
    val bubbleY: Int = -1,

    /**
     * 悬浮球**球心**相对屏幕宽/高的比例（0~1）；-1 表示还没拖动过。
     *
     * 为什么必须存比例：竖屏时球停在右侧、屏幕高度 42% 处，屏幕一转成横屏，
     * 那个绝对 y 就掉到屏幕下方去了 —— 用户看到的现象正是「一横屏小球就没了」。
     * 按比例记就不会：竖屏的右上角在横屏里仍然是右上角。
     *
     * 旧版本只存过绝对像素，首次升级时由它反推比例（见 `OverlayRenderer.resolveBubblePosition`），
     * 并且会立刻 clamp 回可见区域，所以老用户第一次升级就能看到球回到屏幕里。
     */
    val bubbleXFrac: Float = -1f,
    val bubbleYFrac: Float = -1f,

    /** 是否绘制译文覆盖层（关掉后仍会识别翻译，只是不画出来） */
    val overlayEnabled: Boolean = true,
    val fontSizeScale: Float = 1f,
    val textColor: Int = 0xFFFFEB3B.toInt(),
    val scrimColor: Int = 0xFF11131A.toInt(),
    val scrimAlpha: Float = 0.72f,
    val offsetXDp: Int = 0,
    val offsetYDp: Int = 0,

    // ---------------------------------------------------------- 云端配置

    val llmProviderId: String = Defaults.LLM_PROVIDER,
    val llmApiKey: String = "",
    /** 留空 = 用服务商默认地址 */
    val llmBaseUrl: String = "",
    /** 留空 = 用服务商默认模型 */
    val llmModel: String = "",

    /** 混合模式下是否再附一张缩小截图当语境（关掉后任何纯文本模型都能用） */
    val attachScreenshot: Boolean = true,

    /**
     * 是否要求模型用 JSON 严格输出（OpenAI 兼容协议下会带上 `response_format`）。
     * 少部分中转网关不认这个字段会直接 400，遇到时会自动去掉它重试一次。
     */
    val forceJsonMode: Boolean = true,

    /**
     * 是否关闭「思考过程」。
     *
     * 阿里云百炼（DashScope）的 OpenAI 兼容端点对 Qwen3 系列要求：
     * **非流式请求必须显式传 `enable_thinking=false`**，否则报 400
     * 或者把正文挤进 `reasoning_content` 导致正文为空 —— 这正是「模型返回了
     * 但拿不到译文块」的常见原因。Gemini 2.5 则用 thinkingBudget=0 达到同样效果。
     */
    val disableThinking: Boolean = true,

    /**
     * 附加请求参数（JSON 对象字符串），会合并进请求体，优先级最高。
     *
     * 给那些有特殊要求的服务商/自建网关留的活口，例如
     * `{"enable_thinking": false}`、`{"top_k": 1}`、`{"repetition_penalty": 1.05}`。
     * 有了它，遇到没见过的接口怪癖不用等新版本。
     */
    val extraRequestJson: String = "",

    val uploadMaxSide: Int = Defaults.UPLOAD_MAX_SIDE,
    val jpegQuality: Int = Defaults.JPEG_QUALITY,

    // ---------------------------------------------------------- 引导

    /** 教程是否已经完整看过一遍（用于首页显示「先看教程」提示） */
    val tutorialSeen: Boolean = false,
)
