package com.saltin.screentranslator.translate.offline

/** 模型包里需要下载的一个文件 */
data class ModelFile(
    /** 在仓库里的相对路径，如 `onnx/encoder_model_quantized.onnx` */
    val path: String,
    /**
     * **体积估算值**，只用来算「下载前够不够空间」和「首页显示约多少 MB」。
     *
     * ⚠️ 绝不能拿它当下载完成的校验基准：上游随时可能重新导出模型，字节数会变，
     * 而且这些值本身就是四舍五入来的。v1.5 就是这么错的 ——
     * 真实 52899742 字节的文件被 52844544 的估算值判成「不完整」，
     * 于是两个模型都卡在「下到一半报错」。
     *
     * 真正的校验基准见 [OfflineModelStore]：服务端 `Content-Length` 探测 + 本地 manifest。
     */
    val bytes: Long,
)

/**
 * 分词器的形态。决定 App 侧用哪一种加载方式。
 *
 * | 形态 | 词表 | 来源 | 例子 |
 * | --- | --- | --- | --- |
 * | [HUGGINGFACE] | 单个 `tokenizer.json` | Xenova 已转好的仓库 | opus-mt-en-zh |
 * | [TC_BIG] | 自定义 `tokenizer.json`（含 source/target 两套词表） | 本项目从 `*.spm` 合成 | tc-big-zh-ja |
 */
enum class TokenizerKind {
    /** Xenova 仓库里的标准 `tokenizer.json`（Unigram，单表） */
    HUGGINGFACE,

    /** 我们自己合成的 `tokenizer.json`：源/目标两套独立词表 */
    TC_BIG,
}

/** 一个可下载的模型包（一组文件 + 体积） */
data class ModelPack(
    /** 稳定标识，用来在本地目录里区分（如 `opus-mt-en-zh` / `tc-big-zh-ja`） */
    val id: String,
    /** 给用户看的名字 */
    val label: String,
    /** 下载源上的仓库名，如 `Xenova/opus-mt-en-zh` */
    val repo: String,
    val files: List<ModelFile>,
    /** 分词器形态。默认是 Xenova 的标准单表 */
    val tokenizerKind: TokenizerKind = TokenizerKind.HUGGINGFACE,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
    val totalMb: Int get() = (totalBytes / 1048576L).toInt()
}

/**
 * 一条翻译路线要用到的全部模型包。绝大多数情况只有一个（直接对译）。
 *
 * 但 Opus-MT 是**按语言对**训练的，上游没训「日→中」这种方向，
 * 这时只能**拿英语当中转**，两个模型接起来跑（[isPivot] 为 true）。
 */
data class ModelRoute(
    val packs: List<ModelPack>,
    /** 是否经过英语中转（需要下载两个模型） */
    val isPivot: Boolean,
) {
    val totalMb: Int get() = packs.sumOf { it.totalMb }

    /** 「ja→en → en→zh」这种给人看的说法 */
    val pivotLabel: String
        get() = packs.joinToString(" → ") { it.label.removePrefix("Opus-MT ") }
}

/**
 * 离线模型的清单。
 *
 * ## 三种模型来源（v1.6.2）
 *
 * 1. **[OPUS_DIRECT] + Xenova**：上游已经把常用方向导成 ONNX 并发在 `Xenova/opus-mt-*`，
 *    直接下就行，约 115MB / 对（int8 量化）。这是绝大多数方向的来源。
 * 2. **[OPUS_TC_BIG_DIRECT] + 本项目自建仓库**：上游**没有** ONNX 的方向（如中→日），
 *    但 Helsinki-NLP 有 tc-big 的 PyTorch 权重 —— 本项目自行转成 ONNX 并托管，
 *    见 `docs/`。tc-big 是 transformer-big、质量更高（中→日 BLEU 24.6），
 *    代价是体积更大（约 400MB 起）且需要**双 SPM 词表**的分词器。
 * 3. **英语接力**：两头都有英语方向时，串起两个模型跑。
 *
 * ## 直译优先（v1.6.2 核心）
 *
 * 用户要求「中英中日中韩这些主要的至少需要直译，而不是中转」。
 * 所以 [opusRoute] 的顺序是：**先查直译表（含 tc-big），命中就用直译；查不到才接力**。
 *
 * ## 三档的关系
 *
 * [OfflineEngineKind.OPUS_MT]（增强）与 [OPUS_FULL]（全能）用的是**同一套**模型，
 * 区别只在**路由时允许的覆盖面**：全能档允许更多方向（含部分更冷门的接力组合）。
 * 两档都会在缺直译时用英语接力，保证「选了就能翻」。详见 [routeFor]。
 */
object OfflineModelCatalog {

    /** 默认下载源。官方 `huggingface.co` 在部分网络下不可达，所以默认走国内镜像 */
    const val DEFAULT_HOST = "hf-mirror.com"

    /** 量化版模型所在的目录 */
    private const val ONNX_DIR = "onnx"

    /** 三个文件的固定名字（下载后落在同一个目录里，所以只用文件名） */
    const val ENCODER_NAME = "encoder_model_quantized.onnx"
    const val DECODER_NAME = "decoder_model_merged_quantized.onnx"
    const val TOKENIZER_NAME = "tokenizer.json"

    private const val ENCODER_PATH = "$ONNX_DIR/$ENCODER_NAME"
    private const val DECODER_PATH = "$ONNX_DIR/$DECODER_NAME"

    private fun mb(value: Double): Long = (value * 1048576.0).toLong()

    /** 下载成功后写在本地的尺寸清单文件名（见 [OfflineModelStore]） */
    const val MANIFEST_NAME = "manifest.json"

    /**
     * 本项目自建模型（tc-big 转换产物）的托管仓库。
     *
     * 我们没有 HuggingFace 官方模型的发布权，所以这批自转模型放在**项目作者自己的账号**下：
     * `SaltinA/screentranslator-models`（公开仓库，已实测 9 个文件全部可下载）。
     *
     * 仓库内按方向分子目录，**方向属于「文件路径」而不是「仓库名」**：
     * ```
     * 仓库名：SaltinA/screentranslator-models
     * 路径　：tc-big-zh-ja/onnx/encoder_model_quantized.onnx
     * ```
     * 千万不要把方向拼进 repo（会变成 `.../screentranslator-models/tc-big-zh-ja/resolve/...`
     * → 404）。详见 [tcBigFiles]。
     *
     * 实际下载域名由用户在「模型」页配置（默认 [DEFAULT_HOST] 镜像）。
     */
    private const val SELF_REPO_PREFIX = "SaltinA/screentranslator-models"

    // ------------------------------------------------------------------ 直译表（一）：Xenova 现成 ONNX

    /**
     * Opus-MT **直接**可用的语言对（键是 `源码-目标码`，两位小写），
     * 全部来自 HuggingFace 上 `Xenova` 名下的 opus-mt 仓库（已含 int8 量化 ONNX）。
     *
     * 这份清单逐条对应真实存在的仓库，只保留与本 App 语言表相关的那些。
     * 中文统一按 `zh` 处理（简体/繁体共用同一个模型）。
     */
    private val OPUS_DIRECT: Set<String> = setOf(
        // 英语 ↔ 各语言
        "en-ar", "en-de", "en-es", "en-fr", "en-hi", "en-id", "en-it",
        "en-ja", "en-nl", "en-ru", "en-vi", "en-zh",
        "ar-en", "de-en", "es-en", "fr-en", "hi-en", "id-en", "it-en",
        "ja-en", "ko-en", "nl-en", "pl-en", "ru-en", "th-en", "tr-en",
        "vi-en", "zh-en",
        // 欧洲语言之间的直连（上游训过，且用户量不小）
        "de-es", "de-fr", "es-de", "es-fr", "es-it", "es-ru",
        "fr-de", "fr-es", "fr-ru", "it-es", "it-fr", "nl-fr", "ru-es", "ru-fr",
    )

    /**
     * 上游给个别模型起了别名后缀，得按**仓库名**而不是语言码去拼 URL。
     *
     * 目前只有一处：英语→日语叫 `opus-mt-en-jap`（用 `jap` 而不是 `ja`）。
     */
    private val OPUS_SUFFIX: Map<String, String> = mapOf("en-ja" to "en-jap")

    // ------------------------------------------------------------------ 直译表（二）：本项目自转的 tc-big

    /**
     * **本项目自行转换**的 tc-big 直译模型（Helsinki-NLP `opus-mt-tc-big-*`）。
     *
     * 为什么需要这一张表：上游 Xenova 只把一部分方向转成了 ONNX，
     * **中↔日、中↔韩这类最常用的亚洲语言互译恰恰没有**（`ja-zh`/`zh-ja`/`zh-ko`/`ko-zh`
     * 在 Xenova 上全是 404）。而用户明确要求「中日中韩至少要直译，不能中转」。
     *
     * 解法：拿 Helsinki-NLP 的 PyTorch 权重自己转 ONNX。经过实测可用的方向见下
     * （`hf-mirror.com` 上确实存在、且已成功转换的面）。
     *
     * tc-big 的特点：
     * - `transformer-big`（d_model 1024 / ffn 4096 / 16 头），质量高于普通 opus-mt；
     * - **sepvoc**：源/目标各一套 32000 词的 SentencePiece 词表，编码与解码**不能共用**；
     * - 没有语言码前缀，解码起始符是 `<pad>`(32000)、EOS 是 `</s>`(2)。
     */
    private val OPUS_TC_BIG_DIRECT: Set<String> = setOf(
        "zh-ja", // 中→日，BLEU 24.6，用户最需要的方向之一（直接满足「中日直译」）
        "ko-en", // 韩→英
        "en-ko", // 英→韩：中→韩没有直译模型，靠 zh-en(Xenova) + en-ko(这档) 接力；
                 // Xenova 没有 en-ko，所以这一条**必须**由本项目托管
        // 韩→中、日→中、中→韩等方向 Helsinki-NLP 没有单独训（hf-mirror 上 404），
        // 只能靠接力，见 opusRoute 的兜底分支。
    )

    /** 中转用的「枢轴语言」。只有英语（Opus-MT 围绕英语训练，覆盖最全） */
    private const val PIVOT = "en"

    // ------------------------------------------------------------------ 打包

    /** Xenova 单个语言对的三个文件（标准单表分词器） */
    private fun opusFiles(): List<ModelFile> = listOf(
        ModelFile(ENCODER_PATH, mb(50.5)),
        ModelFile(DECODER_PATH, mb(57.5)),
        ModelFile(TOKENIZER_NAME, mb(6.4)),
    )

    /**
     * tc-big 单个语言对的三个文件。
     *
     * 体积为**本项目自转产物的实测值**，量化为两步（见 `docs/tcbig-upload.md`）：
     *   ① MatMul 权重 → int8（递归进 `If` 子图）
     *   ② 125MB 的 embedding 查表矩阵 → int8（在 `Gather` / `Mul` 前插 DequantizeLinear）
     * 结果：encoder ≈112.9MB / decoder ≈171.4MB / tokenizer ≈1.6MB。
     *
     * 只做第 ① 步的话编码器要 211MB、解码器 257MB（那 125MB 的 embedding 表
     * 不是 MatMul 权重，会被常规量化器整个跳过）。
     *
     * ## ⚠ 路径必须带 `tc-big-<key>/` 前缀（这里踩过坑）
     *
     * 自建仓库里三个方向是**同一个仓库下的三个子目录**：
     * ```
     * SaltinA/screentranslator-models/
     *   tc-big-zh-ja/onnx/encoder_model_quantized.onnx
     *   tc-big-ko-en/onnx/encoder_model_quantized.onnx
     * ```
     * 而 HuggingFace 的下载地址形如 `https://host/<repo>/resolve/main/<path>` ——
     * `<repo>` 只能是**仓库名**，子目录是**路径的一部分**。
     *
     * 一开始把方向写进了 `repo`（`"$SELF_REPO_PREFIX/tc-big-$key"`），拼出来是
     * `.../screentranslator-models/tc-big-zh-ja/resolve/main/...`，
     * HF 把 `tc-big-zh-ja` 当仓库名的一部分 → **9 个地址全 404**。
     * 正确做法是 `repo` 只有仓库名，方向并入 `path`。
     */
    private fun tcBigFiles(key: String): List<ModelFile> {
        val prefix = "tc-big-$key/"
        return listOf(
            ModelFile("$prefix$ENCODER_PATH", mb(107.6)),
            ModelFile("$prefix$DECODER_PATH", mb(163.4)),
            ModelFile("$prefix$TOKENIZER_NAME", mb(1.6)),
        )
    }

    /**
     * 单个 Opus-MT 语言对的模型包；该方向上游没有模型时返回 null。
     *
     * 先查本项目自转的 tc-big（质量更高），没有再查 Xenova 的现成 ONNX。
     * 这个优先级保证「能直译就直译，且优先高质量直译」。
     */
    fun opusPack(source: String, target: String): ModelPack? {
        val key = "${base(source)}-${base(target)}"

        if (key in OPUS_TC_BIG_DIRECT) {
            return ModelPack(
                id = "tc-big-$key",
                label = "Opus-MT tc-big ${base(source)}→${base(target)}",
                // ⚠ repo 只写仓库名，方向在 files 的 path 前缀里 —— 见 tcBigFiles 的说明
                repo = SELF_REPO_PREFIX,
                files = tcBigFiles(key),
                tokenizerKind = TokenizerKind.TC_BIG,
            )
        }

        if (key !in OPUS_DIRECT) return null
        val suffix = OPUS_SUFFIX[key] ?: key
        return ModelPack(
            id = "opus-mt-$suffix",
            label = "Opus-MT ${base(source)}→${base(target)}",
            repo = "Xenova/opus-mt-$suffix",
            files = opusFiles(),
            tokenizerKind = TokenizerKind.HUGGINGFACE,
        )
    }

    /**
     * 这个方向该怎么翻：直接一个模型，或者经英语中转用两个模型；都没有就返回 null。
     *
     * @param allowPivot 允不允许英语接力。第二、三档都传 `true`（保证每档都能翻）；
     *   传 `false` 时只有「上游训过的直译对」才算可用。
     *
     * 顺序刻意做成**直译优先**：先查直译表，命中就返回单模型；查不到才考虑接力。
     * 这样用户要的「中日中韩直译」永远优先走直译，只有确实没有直译的方向才两跳。
     *
     * 中转判断：**只要两头都有英语方向的模型就用**。不做「A→B→C 三跳」——
     * 质量会崩，让用户下三个模型也不现实。
     */
    fun opusRoute(source: String, target: String, allowPivot: Boolean = true): ModelRoute? {
        val src = base(source)
        val tgt = base(target)
        if (src == tgt) return null

        // ① 直译优先
        opusPack(src, tgt)?.let { return ModelRoute(listOf(it), isPivot = false) }

        if (!allowPivot) return null

        // ② 英语接力兜底：需要「src→en」和「en→tgt」都存在（任一端本来就是英语就用不上）
        if (src == PIVOT || tgt == PIVOT) return null
        val first = opusPack(src, PIVOT) ?: return null
        val second = opusPack(PIVOT, tgt) ?: return null
        return ModelRoute(listOf(first, second), isPivot = true)
    }

    /**
     * 统一入口：某个引擎 + 某个方向要用哪些模型包。ML Kit 不走托管模型，返回 null。
     *
     * **三档都允许接力**（v1.6.2 修正 v1.6.1 的错误）：用户明确要求「每档都能独立翻译」，
     * 不允许出现「选了某档却连一个方向都翻不了」。两档的差别只在**可下载的方向集合**是否
     * 包含冷门接力组合 —— 见 [OPUS_FULL_ONLY_EXTRA]。
     *
     * 设置页和翻译引擎都从这里取，保证「界面显示要下载什么」和「实际会加载什么」
     * 永远是同一份答案。
     */
    fun routeFor(kind: OfflineEngineKind, source: String, target: String): ModelRoute? =
        when (kind) {
            OfflineEngineKind.ML_KIT -> null
            OfflineEngineKind.OPUS_MT -> opusRoute(source, target, allowPivot = true)
            OfflineEngineKind.OPUS_FULL -> opusRoute(source, target, allowPivot = true)
        }

    /**
     * 「全能」档相对「增强」档多出来的方向。
     *
     * 目前两档路由逻辑一致，这个集合留空即等价；保留它是因为后续若把某些
     * 冷门方向（如需要多段、或需要额外模型的方向）只放进全能档，可以直接在这里登记，
     * 不用再动路由结构。
     */
    private val OPUS_FULL_ONLY_EXTRA: Set<String> = emptySet()

    /** 这个方向 Opus-MT 能不能翻（含经英语中转） */
    fun hasOpusRoute(source: String, target: String): Boolean =
        opusRoute(source, target) != null

    /** 这个方向在给定档位下能不能翻 */
    fun hasOpusRoute(kind: OfflineEngineKind, source: String, target: String): Boolean =
        routeFor(kind, source, target) != null

    /** 拼出某个文件的可下载地址 */
    fun urlOf(host: String, repo: String, path: String): String {
        val cleanHost = host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .ifBlank { DEFAULT_HOST }
        return "https://$cleanHost/$repo/resolve/main/$path"
    }

    /** 语言码统一成模型名里的两位小写基础码 */
    private fun base(language: String): String = when (val code = language.lowercase()) {
        "zh-hant", "zh-tw", "zh-hk", "zh-hans", "zh-cn" -> "zh"
        else -> code.substringBefore('-')
    }
}
