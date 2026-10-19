package com.saltin.screentranslator.translate.offline

import android.util.Log

/**
 * SentencePiece **Unigram** 分词器 —— Opus-MT 用的就是这一套。
 *
 * ## 为什么自己写，而不是引一个库
 *
 * Android 上没有任何可用的 SentencePiece Java 绑定：官方只有 C++/Python，SWIG 版要自己编
 * native + protobuf。而 `tokenizer.json` 里**已经带着完整词表和每个 piece 的对数概率**，
 * Unigram 的编码本质就是「在词表上做一次 Viterbi 最优切分」—— 不到一百行就能实现，
 * 比引一个 native 库轻得多，也不用担心 ABI。
 *
 * ## 前提（不满足就明确报错，不悄悄退化）
 *
 * - `model.type = "Unigram"`，`vocab` 是 `[[piece, score], ...]`（Opus-MT 有 65001 项）
 * - `pre_tokenizer` 是 `Sequence[WhitespaceSplit, Metaspace]`，`add_prefix_space = true`
 * - `normalizer` 是「空 charsmap 的 Precompiled」—— 也就是**不做字符映射**
 *
 * 实测确认过 Opus-MT 这三条都成立（[NormalizerKind.NONE] 就是对应用第三种情况）。
 */
internal class SentencePiece private constructor(
    private val pieces: Array<String>,
    private val scores: FloatArray,
    private val index: HashMap<String, Int>,
    private val unkId: Int,
    private val skipIds: Set<Int>,
    /** 最长的 piece 有多少个**码点**（不是 char）。Viterbi 用它限制枚举窗口 */
    private val maxPieceUnits: Int,
    private val normalizer: NormalizerKind,
) : SubwordTokenizer {

    internal companion object {
        const val TAG = "SentencePiece"

        /** Metaspace 的空格标记 */
        const val SPACE_MARK = '\u2581'

        /** 一个片段都匹配不上时的兜底罚分。给得很负，保证只在无路可走时才用 unk */
        const val UNK_PENALTY = -100.0

        /** 枚举 piece 长度时的硬上限，防止某个超长 piece 把 Viterbi 拖慢 */
        const val MAX_PIECE_HARD_LIMIT = 32

        /** 解码时要跳过的控制 token */
        val BASE_SKIP = setOf("</s>", "<s>", "<pad>", "<unk>", "<mask>")

        fun parse(json: String): SentencePiece {
            val reader = TokJson(json)
            require(reader.seek("model")) {
                "tokenizer.json 里找不到 model 段，分词器无法加载"
            }
            // 进 model 对象内部再查键。少了这两步，指针会停在 `{` 上，
            // 而 pieceScoreArray() 期待的是 `[` —— 会静默返回空词表（见 TokJson 的说明）。
            reader.expect('{')
            reader.enterScope()
            require(reader.seek("vocab")) { "model 段里找不到 vocab，分词器无法加载" }
            val vocab = reader.pieceScoreArray()
            require(vocab.isNotEmpty()) { "Unigram 词表是空的，分词器无法工作" }

            val count = vocab.size
            // 直接建一个填满的数组，不搞 arrayOfNulls + map + toTypedArray 那套 ——
            // 那会在 6.5 万项上多分配两个临时数组，纯属浪费（词汇表本来就在内存里）。
            val pieces = Array(count) { "" }
            val scores = FloatArray(count)
            val index = HashMap<String, Int>(count * 2)
            var maxUnits = 0

            for ((id, pair) in vocab.withIndex()) {
                val piece = pair.first
                pieces[id] = piece
                scores[id] = pair.second
                // 同一个 piece 出现两次时以先出现的（id 更小）为准
                index.putIfAbsent(piece, id)
                // 用码点数而不是 char 数：窗口是拿码点当单位的，用 char 数会偏大（不致命）
                val units = piece.codePointCount(0, piece.length)
                if (units > maxUnits) maxUnits = units
            }

            val unkId = reader.intValue("unk_id") ?: index["<unk>"] ?: 1
            val skipIds = buildSet {
                // ⚠ 注意：这里刻意**不**放入 unkId。
                // `model.unk_id` 是「遇到未知输入时该输出哪个 id」，而 skipIds 是
                // 「解码时该丢掉哪些 id」。两者在 Opus-MT 上刚好错位：
                // 它声明 unk_id = 2，而 vocab[2] 实际是 `,` —— 真把 2 当控制 token 丢掉，
                // 译文里的逗号会全被吃掉。所以 unkId 只用于编码，解码跳过仍按 piece 名判定。
                BASE_SKIP.mapNotNullTo(this) { index[it] }
                // opus-mt 的共享词表里带着 >>cmn_Hans<< 这类语言标记（分数 -100，
                // 正常文本永远不会被切出来）。解码时万一碰到，直接丢掉。
                for ((piece, id) in index) {
                    if (piece.startsWith(">>") && piece.endsWith("<<")) add(id)
                }
            }

            Log.i(TAG, "Unigram 分词器已加载：$count 个 piece，最长 $maxUnits 码点，unk=$unkId")

            return SentencePiece(
                pieces = pieces,
                scores = scores,
                index = index,
                unkId = unkId,
                skipIds = skipIds,
                maxPieceUnits = maxUnits.coerceAtMost(MAX_PIECE_HARD_LIMIT),
                normalizer = OfflineTokenizers.detectNormalizer(json),
            )
        }
    }

    override val encodeVocabSize: Int get() = pieces.size

    override val decodeVocabSize: Int get() = pieces.size

    override val eosId: Int get() = index["</s>"] ?: 0

    // ------------------------------------------------------------ 编码

    override fun encode(text: String, appendEos: Boolean): IntArray {
        val prepared = preTokenize(normalizer.apply(text))
        if (prepared.isEmpty()) return IntArray(0)

        // ⚠ 必须按「码点」而不是 Java 的 char 来切：emoji 这类增补平面字符在 Java 里是
        // 代理对（2 个 char），按 char 迭代会把它拆成两个 unk。实测官方库对
        // `😀`（U+1F600）只产出**一个** unk，而按 char 会产出两个 —— 这正是
        // 「emoji 那一类用例对不上」的根因。
        val offsets = codePointOffsets(prepared)
        val n = offsets.size - 1
        if (n <= 0) return IntArray(0)

        val best = DoubleArray(n + 1)
        val backLen = IntArray(n + 1)
        val backId = IntArray(n + 1)
        java.util.Arrays.fill(best, Double.NEGATIVE_INFINITY)
        best[n] = 0.0

        for (i in n - 1 downTo 0) {
            var matched = false
            val limit = minOf(n, i + maxPieceUnits)
            for (end in (i + 1)..limit) {
                val id = index[prepared.substring(offsets[i], offsets[end])] ?: continue
                val next = best[end]
                if (next == Double.NEGATIVE_INFINITY) continue
                val score = scores[id] + next
                if (!matched || score > best[i]) {
                    best[i] = score
                    backLen[i] = end - i
                    backId[i] = id
                    matched = true
                }
            }
            if (!matched) {
                // 这个词表里一个片段都对不上：按 unk 前进一个码点
                best[i] = UNK_PENALTY + best[i + 1]
                backLen[i] = 1
                backId[i] = unkId
            }
        }

        val raw = ArrayList<Int>(n)
        var cursor = 0
        while (cursor < n) {
            raw += backId[cursor]
            cursor += backLen[cursor].coerceAtLeast(1)
        }

        // 连续未知**合并成一个** unk。
        // 实测官方行为如此：`こんに`（三个字符都不在词表里）只产出 1 个 unk，
        // 而不是 3 个；`かぎ` 同理。不合并的话 token 序列会明显偏长。
        val out = ArrayList<Int>(raw.size + 1)
        for (id in raw) {
            if (id == unkId && out.isNotEmpty() && out.last() == unkId) continue
            out += id
        }
        if (appendEos) out += eosId
        return out.toIntArray()
    }

    /**
     * 每个码点的起始下标，末尾再补一个「字符串长度」作为哨兵。
     * 返回数组长度 = 码点数 + 1。
     */
    private fun codePointOffsets(text: String): IntArray {
        val out = IntArray(text.length + 1)
        var count = 0
        var i = 0
        while (i < text.length) {
            out[count++] = i
            i += Character.charCount(text.codePointAt(i))
        }
        out[count] = text.length
        return out.copyOf(count + 1)
    }

    /**
     * `WhitespaceSplit` + `Metaspace(add_prefix_space=true)`：去掉首尾空白、把空白折成一个 `▁`、
     * 并在开头补一个。效果就是 `"Hello world" → "▁Hello▁world"`，
     * 这样模型看到的每个词都带「前面有空格」的信息。
     *
     * 用 Rust 的 `White_Space` 集合，而不是 Java 的 `Character.isWhitespace` ——
     * 两者对 U+00A0(NBSP)、U+0085(NEL)、U+202F 的判断相反，而链路上游正是 Rust 实现。
     */
    private fun preTokenize(text: String): String {
        val builder = StringBuilder(text.length + 8)
        var pendingSpace = true // add_prefix_space = true
        for (ch in text) {
            if (OfflineTokenizers.isRustWhitespace(ch)) {
                pendingSpace = true
            } else {
                if (pendingSpace) {
                    builder.append(SPACE_MARK)
                    pendingSpace = false
                }
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    // ------------------------------------------------------------ 解码

    override fun decode(ids: IntArray): String {
        val builder = StringBuilder(ids.size * 3)
        for (id in ids) {
            if (id < 0 || id >= pieces.size) continue
            if (id in skipIds) continue
            builder.append(pieces[id])
        }
        // Metaspace 解码：▁ 还原成空格；开头那个是我们自己补的，trim 掉
        return builder.toString().replace(SPACE_MARK, ' ').trim()
    }

    override fun idOf(piece: String): Int? = index[piece]

    override fun pieceOf(id: Int): String = if (id in pieces.indices) pieces[id] else ""

    override fun isSpecial(id: Int): Boolean = id in skipIds
}
