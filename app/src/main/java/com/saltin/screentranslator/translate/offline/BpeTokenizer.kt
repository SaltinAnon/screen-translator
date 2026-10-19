package com.saltin.screentranslator.translate.offline

import android.util.Log

/**
 * SentencePiece **BPE** 分词器 —— NLLB-200 用的是这一套。
 *
 * 和 Opus-MT 那套 Unigram 完全不是一回事，动手前必须分清（否则会「能编译、翻出来是垃圾」）：
 *
 * | | Unigram（Opus-MT） | BPE（NLLB-200） |
 * | --- | --- | --- |
 * | `model.vocab` | `[[piece, score], ...]` 数组，65001 项 | `{piece: id}` 字典，256204 项 |
 * | 切分依据 | 每个 piece 的对数概率 | `merges` 51.4 万条合并优先级 |
 * | `pre_tokenizer` | `WhitespaceSplit` + `Metaspace` | 仅 `Metaspace` |
 *
 * ## BPE 的算法（照 HF `tokenizers` 的语义实现）
 *
 * 1. 归一化（[NormalizerKind.NFKC_APPROX]：NFKC + 换行制表符折成空格 + 连续空格压一个）
 * 2. Metaspace：`空格 → ▁`，若开头不是 `▁` 再补一个
 * 3. 逐字符变成符号：不在词表里的字符落成 `<unk>`
 * 4. 反复取「合并优先级最高（rank 最小）」的相邻符号对合并，直到没有可合并的对
 *
 * 第 4 步的「取全局最小 rank」等价于 HF 用堆实现的结果，短句上直接线性扫足够快。
 *
 * ## 两处内存上的取舍
 *
 * - **merge 不存字符串**：51.4 万条平均 7 字符的规则光字符串就要 ~28MB。这里边读边
 *   压成 `((idA<<18 | idB)<<20 | rank)` 的 `LongArray`（见 [TokJson.packedMerges]），
 *   查询走一次二分，**内存降到 4MB**。
 *   用 id 当键是**充分**的，不是近似：实测 NLLB 的 514098 条 merge 规则里，
 *   操作数不在这 256204 项词表里的条数是 **0**（BPE 的合并产物本身就是词表条目，
 *   所以操作数必然也在词表里）。这一点如果不成立，就必须改成字符串表了。
 * - **vocab 用 HashMap**：BPE 必须按 piece 字符串查 id，这一步省不掉。
 */
internal class BpeTokenizer private constructor(
    private val pieces: Array<String>,
    private val vocab: HashMap<String, Int>,
    private val unkId: Int,
    private val merges: LongArray,
    private val skipIds: Set<Int>,
    private val addPrefixSpace: Boolean,
    private val fuseUnk: Boolean,
    private val normalizer: NormalizerKind,
) : SubwordTokenizer {

    internal companion object {
        const val TAG = "BpeTokenizer"

        const val SPACE_MARK = '\u2581'

        /** 语言码 piece 的形状，如 `eng_Latn` / `zho_Hans`（NLLB 是 200 语言的模型） */
        val LANGUAGE_CODE = Regex("[a-z]{3}_[A-Z][a-z]{3}")

        val BASE_SKIP = listOf("<s>", "<pad>", "</s>", "<unk>", "<mask>")

        fun parse(json: String): BpeTokenizer {
            val reader = TokJson(json)
            require(reader.seek("model")) {
                "tokenizer.json 里找不到 model 段，分词器无法加载"
            }
            reader.expect('{')
            reader.enterScope()

            // 进了作用域之后就不必再关心这几个键在文件里的先后顺序了（见 TokJson 的说明）
            val unkToken = reader.stringValue("unk_token") ?: "<unk>"
            val fuseUnk = reader.boolValue("fuse_unk") ?: false
            require(reader.seek("vocab")) { "model 段里找不到 vocab，分词器无法加载" }
            val vocab = reader.stringIntDict(VOCAB_HINT)
            require(vocab.isNotEmpty()) { "BPE 词表是空的，分词器无法工作" }
            require(reader.seek("merges")) { "model 段里找不到 merges，分词器无法加载" }
            val merges = reader.packedMerges(vocab)

            val unkId = vocab[unkToken] ?: vocab["<unk>"] ?: 3
            val size = vocab.values.maxOrNull()?.plus(1) ?: 0
            require(size > 0) { "BPE 词表里没有有效的 id" }

            // 注意这里用 `Array(size) { "" }` 而不是 arrayOfNulls + map + toTypedArray：
            // 25.6 万项上，后者要额外分配两个临时数组（其中一个还是装箱后的 String），
            // 而这一段的**峰值内存**正是我们在手机上最需要省的地方（实测见 README）。
            val pieces = Array(size) { "" }
            for ((piece, id) in vocab) {
                if (id in 0 until size) pieces[id] = piece
            }

            val skipIds = buildSet {
                BASE_SKIP.mapNotNullTo(this) { vocab[it] }
                for (piece in vocab.keys) {
                    if (LANGUAGE_CODE.matches(piece)) add(vocab.getValue(piece))
                }
            }

            Log.i(
                TAG,
                "BPE 分词器已加载：词表 $size 项，merge 规则 ${merges.size} 条，" +
                    "unk=$unkId，语言码 ${skipIds.size - BASE_SKIP.size} 个",
            )

            return BpeTokenizer(
                pieces = pieces,
                vocab = vocab,
                unkId = unkId,
                merges = merges,
                skipIds = skipIds,
                addPrefixSpace = OfflineTokenizers.detectAddPrefixSpace(json),
                fuseUnk = fuseUnk,
                normalizer = OfflineTokenizers.detectNormalizer(json),
            )
        }

        /** 词表规模的预估值，只用于给 HashMap 定初始容量（少 rehash 几次） */
        const val VOCAB_HINT = 262144
    }

    override val encodeVocabSize: Int get() = pieces.size

    override val decodeVocabSize: Int get() = pieces.size

    override val eosId: Int get() = vocab["</s>"] ?: 2

    // ------------------------------------------------------------ 编码

    override fun encode(text: String, appendEos: Boolean): IntArray {
        val normalized = normalizer.apply(text)
        if (normalized.isEmpty()) return IntArray(0)

        // Metaspace：空格换成 ▁；开头不是 ▁ 就补一个（两个模型的 add_prefix_space 都是 true）
        var marked = if (addPrefixSpace) normalized.replace(' ', SPACE_MARK) else normalized
        if (addPrefixSpace && !marked.startsWith(SPACE_MARK)) {
            marked = SPACE_MARK + marked
        }
        if (marked.isEmpty()) return IntArray(0)

        val symbols = ArrayList<Int>(marked.length)
        // ⚠ 按**码点**而不是 Java char 建初始符号。emoji 等增补平面字符在 Java 里是代理对
        // （2 个 char），按 char 迭代会得到两个互不相干的 unk，后续 `▁` + unk 也就
        // 永远合不出 `▁😀` 这样的词表条目 —— 实测官方对 `emoji 😀 test 🎉`
        // 给出 `▁emo ji ▁😀 ▁test ▁ <unk>`，其中 `▁😀` 是词表里 id 49999 的单个 token。
        var i = 0
        while (i < marked.length) {
            val codePoint = marked.codePointAt(i)
            val unit = String(Character.toChars(codePoint))
            symbols += vocab[unit] ?: unkId
            i += Character.charCount(codePoint)
        }

        merge(symbols)

        val out = IntArray(symbols.size + if (appendEos) 1 else 0)
        for (i in symbols.indices) out[i] = symbols[i]
        if (appendEos) out[symbols.size] = eosId
        return out
    }

    /** 反复合并「优先级最高」的相邻符号对，直到没有可合并的 */
    private fun merge(symbols: ArrayList<Int>) {
        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestAt = -1
            for (i in 0 until symbols.size - 1) {
                val left = symbols[i]
                val right = symbols[i + 1]
                // fuse_unk = false 时，unk 不参与合并（否则会把两段未知文本粘成一个）
                if (!fuseUnk && (left == unkId || right == unkId)) continue
                val rank = TokJson.rankOf(merges, left, right)
                if (rank in 0 until bestRank) {
                    bestRank = rank
                    bestAt = i
                }
            }
            if (bestAt < 0) return
            val merged = pieces[symbols[bestAt]] + pieces[symbols[bestAt + 1]]
            symbols[bestAt] = vocab[merged] ?: unkId
            symbols.removeAt(bestAt + 1)
        }
    }

    // ------------------------------------------------------------ 解码

    override fun decode(ids: IntArray): String {
        val builder = StringBuilder(ids.size * 3)
        for (id in ids) {
            if (id < 0 || id >= pieces.size) continue
            if (id in skipIds) continue
            builder.append(pieces[id])
        }
        return builder.toString().replace(SPACE_MARK, ' ').trim()
    }

    override fun idOf(piece: String): Int? = vocab[piece]

    override fun pieceOf(id: Int): String = if (id in pieces.indices) pieces[id] else ""

    override fun isSpecial(id: Int): Boolean = id in skipIds
}
