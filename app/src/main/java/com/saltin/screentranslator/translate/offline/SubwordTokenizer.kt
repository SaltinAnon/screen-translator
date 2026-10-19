package com.saltin.screentranslator.translate.offline

import android.util.Log
import java.io.File
import java.text.Normalizer

/**
 * 端侧离线翻译要用的子词分词器：文本 ↔ token id。
 *
 * 只暴露翻译真正需要的几件事 —— 引擎层不需要知道背后是 Unigram 还是 BPE。
 *
 * ## 单表 vs 双表（v1.6.2）
 *
 * 绝大多数 Opus-MT 模型是**单表**：编码和解码共用同一个词表（[vocabSize]/[eosId]
 * 这些属性都指它）。但 tc-big（Helsinki-NLP `opus-mt-tc-big-*`）是 **sepvoc** ——
 * 源语言和目标语言**各有一套 32000 词的 SentencePiece 词表，id 空间完全不同**，
 * 编码必须用 `source.spm`、解码必须用 `target.spm`，混用会翻出一堆错字。
 *
 * 所以接口把「编码相关」和「解码相关」分开：
 * - 编码：[encode]、[encodeVocabSize]（源词表）
 * - 解码：[decode]、[decodeVocabSize]、[eosId]、[isSpecial]（目标词表）
 *
 * 单表实现让这几组指向同一个词表即可，行为与旧版完全一致。
 */
interface SubwordTokenizer {

    /** **编码**侧词表大小。单表模型等于 [decodeVocabSize] */
    val encodeVocabSize: Int

    /** **解码**侧词表大小。单表模型等于 [encodeVocabSize] */
    val decodeVocabSize: Int

    /** 旧字段：词表大小。单表模型用；双表模型返回**解码**侧（渲染/裁剪 logits 用解码侧） */
    @Deprecated("改用 encodeVocabSize / decodeVocabSize", ReplaceWith("decodeVocabSize"))
    val vocabSize: Int get() = decodeVocabSize

    /** 句子结束符 id（**解码**侧） */
    val eosId: Int

    /**
     * 文本 → token id。
     *
     * @param appendEos 末尾是否补一个 `</s>`。**喂给模型时必须为 true**：
     *   Marian（Opus-MT）的 post_processor 约定「源句 + `</s>`」，
     *   漏掉它编码器会看到截断的序列，译文明显退化甚至变成重复循环
     *   （实测「The quick brown fox…」会翻成「快速快速的棕狐狐狐狐狐…」）。
     */
    fun encode(text: String, appendEos: Boolean = false): IntArray

    /** token id → 文本。控制 token 与语言码自动丢弃 */
    fun decode(ids: IntArray): String

    /** piece → id（**编码**侧词表）；词表里没有返回 null */
    fun idOf(piece: String): Int?

    /**
     * piece → id（**解码**侧词表）；词表里没有返回 null。
     *
     * 专门给「解码起始符」用：那个 id 是**要喂给 decoder** 的，
     * 属于目标表 id 空间。
     *
     * 单表模型（绝大多数 Opus-MT）两张表是同一张，默认实现直接转给 [idOf] 即可；
     * 双表模型（tc-big / sepvoc）必须覆盖它，否则会在「两表 special 不一致」的模型上翻错。
     */
    fun idOfInTarget(piece: String): Int? = idOf(piece)

    /** id → piece（**解码**侧词表）；越界返回空串 */
    fun pieceOf(id: Int): String

    /** 这个 id 是不是控制 token（`</s>` / `<pad>` / `>>cmn_Hans<<` / `eng_Latn` 之类，**解码**侧） */
    fun isSpecial(id: Int): Boolean
}

/**
 * `tokenizer.json` 里 normalizer 的类型。
 *
 * 为什么要区分：**Opus-MT 的 normalizer 是空的**（`Precompiled` 但 charsmap 为 `null`），
 * 也就是不做任何字符映射；而 NLLB 带一个真实的 SentencePiece 预编译字符映射表
 * （本质是 NMT 归一化：NFKC + 换行制表符折成空格 + 连续空格压缩）。
 *
 * 对 Opus 若擅自套 NFKC，全角输入会被改写、和官方行为对不上；
 * 对 NLLB 若不做归一化，token 切分同样会偏。
 */
internal enum class NormalizerKind(private val label: String) {

    /** 不做任何归一化（Opus-MT） */
    NONE("不归一化"),

    /**
     * 用 NFKC 近似 NLLB 的预编译字符映射。
     *
     * 近似而非精确：那段 `precompiled_charsmap` 是 SentencePiece 的 Darts 双数组字典 +
     * 替换表二进制块，要在 App 里完整复刻代价很大。实测（24 条覆盖全角、CJK、
     * 假名、西里尔、emoji、换行制表符的用例）NFKC 的结果与官方库**逐 token 一致**，
     * 差异只可能出现在极少见的控制字符上。
     */
    NFKC_APPROX("NFKC 近似"),
    ;

    /** 套用这套归一化 */
    fun apply(text: String): String = when (this) {
        NONE -> text
        NFKC_APPROX -> nfkcCollapse(text)
    }

    override fun toString(): String = label

    private fun nfkcCollapse(text: String): String {
        val normalized = runCatching { Normalizer.normalize(text, Normalizer.Form.NFKC) }
            .getOrDefault(text)
        val builder = StringBuilder(normalized.length)
        var lastWasSpace = false
        for (ch in normalized) {
            // 预编译映射会把换行/制表符折成空格（NFKC 不管这些）
            val mapped = when (ch) {
                '\n', '\t', '\r', '\u000B', '\u000C', '\u0085' -> ' '
                else -> ch
            }
            if (mapped == ' ') {
                // 对应 `Replace(" {2,}" -> " ")`：连续空格压成一个
                if (lastWasSpace) continue
                lastWasSpace = true
            } else {
                lastWasSpace = false
            }
            builder.append(mapped)
        }
        return builder.toString()
    }
}

/**
 * 按 `tokenizer.json` 里的 `model.type` 选一个分词器实现。
 *
 * | | Opus-MT | NLLB-200 |
 * | --- | --- | --- |
 * | `model.type` | `Unigram` | `BPE` |
 * | `vocab` 形状 | `[[piece, score], ...]` 数组 | `{piece: id}` 字典 |
 * | 词表大小 | 65001 | 256204 |
 * | 额外字段 | 每个 piece 带一个对数概率 | `merges` 51.4 万条 |
 *
 * 遇到别的类型会明确抛错，而不是悄悄退化 —— 分词错了译文必然错，
 * 早点炸出来比翻出一屏乱码好定位。
 */
object OfflineTokenizers {

    private const val TAG = "OfflineTokenizers"

    private const val SUPPORTED_HINT =
        "模型的分词器格式不受支持。请在设置页换用其他离线引擎，或重新下载模型。"

    /**
     * 加载分词器。
     *
     * 支持两种形态（见 [TokenizerKind]）：
     * - **标准 HuggingFace**（Xenova 的 `tokenizer.json`）：`model.vocab` 单表；
     * - **本项目 tc-big**：`model.vocab`（源表）+ `target_model.vocab`（目标表）双表。
     *
     * 靠「有没有 `target_model` 段」自动判别，不需要调用方额外传参 ——
     * 下载完的文件自己就说清了它是哪种。
     */
    fun load(file: File): SubwordTokenizer {
        if (!file.isFile) throw IllegalStateException("分词器文件不存在：${file.name}")
        val text = file.readText()

        // 双表（tc-big）优先判别：它有 target_model 段
        if (text.contains("\"target_model\"")) {
            Log.i(TAG, "检测到双词表分词器（tc-big / sepvoc）")
            return DualVocabSentencePiece.parse(text)
        }

        // 必须先看 model.type。注意要从 "model" 这个键之后开始找 ——
        // normalizer / pre_tokenizer / post_processor 各自也有一个 "type" 字段。
        val probe = TokJson(text)
        if (!probe.seek("model")) {
            throw IllegalStateException("tokenizer.json 里没有 model 段。$SUPPORTED_HINT")
        }
        val type = probe.stringValue("type")?.lowercase()
        Log.i(TAG, "分词器类型：$type")

        return when (type) {
            "unigram" -> SentencePiece.parse(text)
            "bpe" -> BpeTokenizer.parse(text)
            "wordpiece" -> throw IllegalStateException("WordPiece 分词器暂不支持。$SUPPORTED_HINT")
            else -> throw IllegalStateException("未知的分词器类型「$type」。$SUPPORTED_HINT")
        }
    }

    // ------------------------------------------------------------ 配置探测

    /**
     * 判断 normalizer 的类型。只看「`precompiled_charsmap` 的值是不是 null」——
     * 这是两个模型唯一的分歧点。
     */
    internal fun detectNormalizer(json: String): NormalizerKind {
        val start = json.indexOf("\"normalizer\"")
        if (start < 0) return NormalizerKind.NONE
        val end = json.indexOf("\"pre_tokenizer\"", start).let { if (it < 0) start + 4096 else it }
        // 去掉空白后再判断，免得被格式差异绊倒（这段最多几千字符，开销可忽略）
        val span = json.substring(start, minOf(end, start + 8192))
            .filterNot { it.isWhitespace() || it == '\n' }
        if (span.startsWith("\"normalizer\":null")) return NormalizerKind.NONE
        val at = span.indexOf("\"precompiled_charsmap\"")
        if (at < 0) return NormalizerKind.NONE
        val colon = span.indexOf(':', at)
        if (colon < 0) return NormalizerKind.NONE
        return if (span.startsWith("null", colon + 1)) {
            NormalizerKind.NONE
        } else {
            NormalizerKind.NFKC_APPROX
        }
    }

    /**
     * `pre_tokenizer` / `normalizer` 里的 `add_prefix_space`。
     * 两个模型都是 `true`，取不到时也按 `true` 处理（Metaspace 的常规配置）。
     */
    internal fun detectAddPrefixSpace(json: String): Boolean {
        val start = json.indexOf("\"pre_tokenizer\"")
        if (start < 0) return true
        val end = json.indexOf("\"post_processor\"", start).let { if (it < 0) start + 4096 else it }
        val at = json.indexOf("\"add_prefix_space\"", start)
        if (at < 0 || at > end) return true
        val colon = json.indexOf(':', at)
        if (colon < 0) return true
        val tail = json.substring(colon + 1, minOf(colon + 12, json.length))
        return !tail.trimStart().startsWith("false")
    }

    // ------------------------------------------------------------ 空白判定

    /**
     * Rust `char::is_whitespace` 即 Unicode `White_Space=yes` 的完整集合。
     *
     * 为什么不直接用 Java 的 `Character.isWhitespace`：两者判断相反的地方不少 ——
     * Java 认为 U+00A0(NBSP)、U+202F、U+0085(NEL) 不是空白，Rust 认为是；
     * 反过来 Java 把 U+001C~U+001F 算作空白，Rust 不算。
     * 而链路上游（HF 的 `WhitespaceSplit` / `Metaspace`）是 Rust 实现，
     * 所以这里手工对齐 Rust 的定义。
     */
    internal fun isRustWhitespace(ch: Char): Boolean = when (ch.code) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D -> true          // TAB LF VT FF CR
        0x20 -> true                                   // SPACE
        0x85 -> true                                   // NEL
        0xA0 -> true                                   // NBSP
        0x1680 -> true                                 // OGHAM SPACE MARK
        in 0x2000..0x200A -> true                      // EN QUAD .. HAIR SPACE
        0x2028, 0x2029 -> true                         // LINE / PARAGRAPH SEPARATOR
        0x202F -> true                                 // NARROW NBSP
        0x205F -> true                                 // MEDIUM MATHEMATICAL SPACE
        0x3000 -> true                                 // IDEOGRAPHIC SPACE
        else -> false
    }
}
