package com.saltin.screentranslator.translate.offline

import android.util.Log

/**
 * **双词表** SentencePiece Unigram 分词器 —— 给 tc-big（sepvoc）用。
 *
 * ## 为什么不能直接复用 [SentencePiece]
 *
 * [SentencePiece] 假设「编码和解码共用一张词表」。普通 Opus-MT 成立，tc-big 不成立：
 * 它是 Helsinki-NLP 的 **sepvoc**（separate vocabulary）训练产物，
 * 源语言与目标语言各有独立的 32000 词表，**同一个 id 在两张表里是完全不同的 piece**：
 *
 * ```
 *      id 5   →  源表是 '의'（韩语助词）   目标表是 '▁the'
 *      id 6   →  源表是 '▁'                目标表是 's'
 * ```
 *
 * 实测确认：tc-big-ko-en 两张表有 31995 / 32000 个 id 位置不同。
 * 如果拿源表去解码模型输出，翻出来的会是一串毫无意义的韩文字母 ——
 * 这正是「胡乱翻译」的又一种成因。所以**编码必须走源表、解码必须走目标表**。
 *
 * ## 与 [SentencePiece] 的关系
 *
 * 编码 / 解码的算法（Viterbi 最优切分、Metaspace 预处理、码点迭代、连续 unk 合并）
 * 与 [SentencePiece] 完全一致，只是「用哪张表」不同。这里通过持有**两个**
 * `SentencePiece` 实例（[encoder] / [decoder]）复用同一套逻辑，避免复制粘贴。
 *
 * ## 文件格式（本项目自建，见 convert_tcbig.py）
 *
 * ```json
 * {
 *   "model":        { "type": "Unigram", "vocab": [[piece, score], ...] },   // 源表
 *   "target_model": { "type": "Unigram", "vocab": [[piece, score], ...] },   // 目标表
 *   "source_specials": { "unk":0, "bos":1, "eos":2, "pad":-1 },
 *   "target_specials": { "unk":0, "bos":1, "eos":2, "pad":-1 },
 *   ...
 * }
 * ```
 */
internal class DualVocabSentencePiece private constructor(
    private val encoder: SentencePiece,
    private val decoder: SentencePiece,
    private val targetSkipIds: Set<Int>,
) : SubwordTokenizer {

    internal companion object {
        private const val TAG = "DualVocabSP"

        /**
         * 把 tc-big 的 tokenizer.json 拆成两个单表 [SentencePiece] 再拼起来。
         *
         * 做法：从原始 JSON 里分别抽出 `model` 段和 `target_model` 段，
         * 各自拼一个「标准 Unigram tokenizer.json」风格的子串交给
         * [SentencePiece.parse] —— 这样算法部分一行都不用重写。
         */
        fun parse(json: String): DualVocabSentencePiece {
            val sourceJson = buildSingleVocabJson(json, "model")
            val targetJson = buildSingleVocabJson(json, "target_model")

            val src = SentencePiece.parse(sourceJson)
            val tgt = SentencePiece.parse(targetJson)
            Log.i(
                TAG,
                "双词表分词器已加载：源 ${src.encodeVocabSize} / 目标 ${tgt.decodeVocabSize}，" +
                    "eos=${tgt.eosId}",
            )

            // 解码时该跳过的控制 token：取目标表的 skip 集合
            val skip = buildSet {
                for (name in SentencePiece.BASE_SKIP) {
                    tgt.idOf(name)?.let { add(it) }
                }
                // 语言码（>>cmn_Hans<< 之类）在 sepvoc 目标表里一般没有，但保留防御
                for (id in 0 until tgt.decodeVocabSize) {
                    val piece = tgt.pieceOf(id)
                    if (piece.startsWith(">>") && piece.endsWith("<<")) add(id)
                }
            }

            return DualVocabSentencePiece(src, tgt, skip)
        }

        /**
         * 从完整 JSON 里抽出某一段（`model` 或 `target_model`），
         * 拼成 [SentencePiece.parse] 能吃的「单表 tokenizer.json」文本。
         *
         * 因为 [SentencePiece.parse] 只关心 `model.vocab` / `model.unk_id` /
         * `normalizer` / `pre_tokenizer` 这几处，这里把 vocab 段整体搬运，
         * 其余字段按 tc-big 的已知规格补上（normalizer 空、Metaspace + add_prefix_space）。
         */
        private fun buildSingleVocabJson(json: String, section: String): String {
            val vocabSpan = extractArray(json, "\"$section\"")
                ?: throw IllegalStateException("tokenizer.json 里找不到 $section.vocab")

            // 源/目标表都带 unk_id；从对应 specials 段取，取不到按 0
            val specialsKey = if (section == "model") "source_specials" else "target_specials"
            val unkId = extractInt(json, specialsKey, "unk") ?: 0

            return """
            {
              "model": { "type": "Unigram", "vocab": $vocabSpan, "unk_id": $unkId },
              "normalizer": { "type": "Precompiled", "precompiled_charsmap": null },
              "pre_tokenizer": {
                "type": "Sequence",
                "pretokenizers": [
                  { "type": "WhitespaceSplit" },
                  { "type": "Metaspace", "replacement": "\u2581", "add_prefix_space": true }
                ]
              }
            }
            """.trimIndent()
        }

        /** 取 `"key"` 后面那个 `[...]` 数组的原始文本（含方括号） */
        private fun extractArray(json: String, key: String): String? {
            val at = json.indexOf(key)
            if (at < 0) return null
            var i = json.indexOf('[', at)
            if (i < 0) return null
            val start = i
            var depth = 0
            var inString = false
            var escaped = false
            while (i < json.length) {
                val c = json[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) return json.substring(start, i + 1)
                        }
                    }
                }
                i++
            }
            return null
        }

        /** 取 `"section": { ... "key": N }` 里的整数 N */
        private fun extractInt(json: String, section: String, key: String): Int? {
            val at = json.indexOf("\"$section\"")
            if (at < 0) return null
            val end = json.indexOf('}', at).let { if (it < 0) json.length else it }
            val span = json.substring(at, end)
            val kAt = span.indexOf("\"$key\"")
            if (kAt < 0) return null
            val colon = span.indexOf(':', kAt)
            if (colon < 0) return null
            var j = colon + 1
            while (j < span.length && span[j].isWhitespace()) j++
            val numStart = j
            if (j < span.length && (span[j] == '-' || span[j].isDigit())) j++
            while (j < span.length && span[j].isDigit()) j++
            return span.substring(numStart, j).toIntOrNull()
        }
    }

    // ------------------------------------------------------------ 接口实现

    /** 编码走**源**表 */
    override val encodeVocabSize: Int get() = encoder.encodeVocabSize

    /** 解码走**目标**表 */
    override val decodeVocabSize: Int get() = decoder.decodeVocabSize

    /** EOS 取目标表的 `</s>` —— 解码终止判据用的是目标词表 */
    override val eosId: Int get() = decoder.eosId

    override fun encode(text: String, appendEos: Boolean): IntArray =
        encoder.encode(text, appendEos)

    override fun decode(ids: IntArray): String = decoder.decode(ids)

    /**
     * ⚠ 这里返回的是**源表**里该 piece 的 id。
     *
     * 编码侧（`<unk>`、`</s>` 之类用于「本段输入」的符号）用源表是对的；
     * 但**解码起始符 `<pad>` 属于目标表 id 空间**（它是要喂给 decoder 的），
     * 必须用 [idOfInTarget]。
     *
     * tc-big 实测两张表的 specials 恰好一致（unk0/bos1/eos2），所以用错也看不出问题；
     * 但换成两表特殊 id 不同的 sepvoc 模型就会直接翻错 —— 不能靠巧合。
     */
    override fun idOf(piece: String): Int? = encoder.idOf(piece)

    /**
     * 目标表里该 piece 的 id。
     *
     * 给「解码起始符」「EOS 判据」这类**解码侧**用途。
     * tc-big 的 `<pad>` 在目标表里是 32000（源表里也是，因为两表同源同 specials）。
     */
    override fun idOfInTarget(piece: String): Int? = decoder.idOf(piece)

    override fun pieceOf(id: Int): String = decoder.pieceOf(id)

    override fun isSpecial(id: Int): Boolean =
        id in targetSkipIds || decoder.isSpecial(id)
}
