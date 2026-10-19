package com.saltin.screentranslator.translate.offline

/**
 * 极简 JSON 扫描器 —— 只为读 `tokenizer.json` 的 `model` 段而存在。
 *
 * ## 为什么不用 org.json
 *
 * NLLB 的 `tokenizer.json` 有 16.5MB：一个 **25.6 万条目**的词表字典，外加
 * **51.4 万条** merge 规则。用 org.json 把整棵对象树建出来，中间对象光内存就要上百 MB，
 * Android 上大概率直接被低内存杀手干掉。
 *
 * 而分词真正需要的只有三个字段：`model.type` / `model.vocab` / `model.merges`。
 * 所以这里做**单向线性扫描**：指针只前进、不建树，只在必要处分配最终结果。
 *
 * ## 使用约定
 *
 * 读一个子对象里的多个键时，必须先用 [enterScope] 把搜索起点固定在那个对象上。
 * 原因是所有读方法都基于 [seek] 的**单向扫描**（指针只前进、不回头）——
 * 那样就绑定了「键在文件里出现的先后顺序」和「你调用的顺序」必须一致，
 * 而这个顺序在不同模型的 `tokenizer.json` 里并不一样（实测 NLLB 的 `model` 段里
 * `fuse_unk` 就在 `vocab` 之前，而代码里先读 `unk_token`）。
 * 有了作用域，`seek` 每次都从对象起点重新找，调用顺序就自由了。
 *
 * 所有读方法都会跳过多余空白，遇到结构不符**不会抛异常**，而是返回空结果 ——
 * 调用方据此走「模型格式不受支持」的分支，比在半途抛出 `JSONException` 好定位得多。
 */
internal class TokJson(private val text: String) {

    private var pos = 0

    /**
     * 子对象的搜索起点；-1 表示未进入作用域。
     *
     * 进入作用域后 [seek] 一律从 `min(scopeFrom, pos)` 开始找，
     * 于是「同一个对象里查多个键」不再要求调用顺序与文件键顺序一致。
     */
    private var scopeFrom = -1

    /** 进入子对象作用域（调用点应停在 `{` 之后） */
    fun enterScope() {
        scopeFrom = pos
    }

    /** 离开子对象作用域，恢复「只从当前位置往前找」 */
    fun leaveScope() {
        scopeFrom = -1
    }

    /** 把指针移到 `"key"` 后面的冒号之后；找不到返回 false（指针不动） */
    fun seek(key: String): Boolean {
        val needle = StringBuilder(key.length + 2).append('"').append(key).append('"').toString()
        val from = if (scopeFrom >= 0) minOf(scopeFrom, pos) else pos
        val at = text.indexOf(needle, from)
        if (at < 0) return false
        var i = at + needle.length
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != ':') return false
        pos = i + 1
        return true
    }

    /** 从当前位置往后找 `"key"`，把指针放在它后面；用于「同一个键名出现多次」的场景 */
    fun seekNext(key: String): Boolean = seek(key)

    fun skipSpaces() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    fun expect(ch: Char) {
        skipSpaces()
        if (pos < text.length && text[pos] == ch) pos++
    }

    /** 读一个 JSON 字符串值（会跳过前导空白）。读不到返回空串 */
    fun readString(): String {
        skipSpaces()
        if (pos >= text.length || text[pos] != '"') return ""
        pos++
        val builder = StringBuilder(24)
        while (pos < text.length) {
            when (val ch = text[pos++]) {
                '"' -> return builder.toString()
                '\\' -> {
                    if (pos >= text.length) break
                    when (val esc = text[pos++]) {
                        'n' -> builder.append('\n')
                        't' -> builder.append('\t')
                        'r' -> builder.append('\r')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        '/' -> builder.append('/')
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        'u' -> {
                            if (pos + 4 <= text.length) {
                                val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                if (code != null) {
                                    builder.append(code.toChar())
                                    pos += 4
                                }
                            }
                        }
                        else -> builder.append(esc)
                    }
                }
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    /** 读一个 JSON 数字（返回 Float；整数也能读） */
    fun readNumber(): Float {
        skipSpaces()
        val start = pos
        while (pos < text.length) {
            val ch = text[pos]
            val ok = ch.isDigit() || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E'
            if (!ok) break
            pos++
        }
        if (pos == start) return 0f
        return text.substring(start, pos).toFloatOrNull() ?: 0f
    }

    /** 读一个 JSON 整数（用于 `{"piece": 123}` 这类字典的值） */
    fun readInt(): Int {
        skipSpaces()
        val start = pos
        while (pos < text.length) {
            val ch = text[pos]
            if (ch.isDigit() || ch == '-') pos++ else break
        }
        if (pos == start) return -1
        return text.substring(start, pos).toIntOrNull() ?: -1
    }

    // ------------------------------------------------------------ 具名取值

    /** `"key": "值"` */
    fun stringValue(key: String): String? {
        if (!seek(key)) return null
        val value = readString()
        return value.ifEmpty { null }
    }

    /** `"key": 数字` */
    fun intValue(key: String): Int? {
        if (!seek(key)) return null
        skipSpaces()
        val start = pos
        while (pos < text.length) {
            val ch = text[pos]
            if (ch.isDigit() || ch == '-') pos++ else break
        }
        if (pos == start) return null
        return text.substring(start, pos).toIntOrNull()
    }

    /** `"key": true / false` */
    fun boolValue(key: String): Boolean? {
        if (!seek(key)) return null
        skipSpaces()
        return when {
            text.startsWith("true", pos) -> { pos += 4; true }
            text.startsWith("false", pos) -> { pos += 5; false }
            else -> null
        }
    }

    // ------------------------------------------------------------ 词表

    /**
     * Unigram 的 `vocab`：`[["piece", score], ...]`。
     *
     * Opus-MT 是 65001 项，内存上完全吃得消，所以直接返回 `List`。
     */
    fun pieceScoreArray(): List<Pair<String, Float>> {
        skipSpaces()
        if (peek() != '[') return emptyList()
        pos++
        val result = ArrayList<Pair<String, Float>>(70000)
        while (true) {
            skipSpaces()
            if (peek() == ']') break
            if (peek() != '[') break
            pos++
            val piece = readString()
            expect(',')
            val score = readNumber()
            expect(']')
            result += piece to score
            skipSpaces()
            when (peek()) {
                ',' -> pos++
                else -> break
            }
        }
        return result
    }

    /**
     * BPE 的 `vocab`：`{"piece": id, ...}` —— NLLB 有 25.6 万项。
     *
     * 返回的 `HashMap` 预先给足容量，避免扩容时反复 rehash（25 万条 rehash 一次很贵）。
     */
    fun stringIntDict(entryHint: Int): HashMap<String, Int> {
        val map = HashMap<String, Int>(entryHint * 4 / 3 + 16)
        skipSpaces()
        if (peek() != '{') return map
        pos++
        while (true) {
            skipSpaces()
            if (peek() == '}') break
            if (peek() != '"') break
            val key = readString()
            expect(':')
            val id = readInt()
            if (key.isNotEmpty() && id >= 0) {
                // 同一个 piece 出现两次时以先出现的（通常 id 更小）为准
                map.putIfAbsent(key, id)
            }
            skipSpaces()
            when (peek()) {
                ',' -> pos++
                else -> break
            }
        }
        return map
    }

    /**
     * BPE 的 `merges`：`["a b", "c d", ...]` —— NLLB 有 51.4 万条。
     *
     * ## 为什么不把 merge 存成字符串表
     *
     * 51.4 万条平均 7 字符的字符串，光对象头加上字符数组就要 ~28MB。
     * 而我们真正需要的信息只有两件：**这对 piece 是哪两个 id**、**它的优先级排第几**。
     * 于是这里边读边把它压成一个 `LongArray`：
     *
     * ```
     * packed = ((idA << 18 | idB) << 20) | rank
     *          └──── 36 位 ────┘        └ 20 位 ┘
     * ```
     *
     * （NLLB 词表 25.6 万 < 2^18，merge 数 51.4 万 < 2^20，都放得下。）
     * 排序后同一个 key 自然靠在一起，查优先级用一次二分即可，
     * 内存从 ~28MB 降到 **4MB**。
     *
     * @return 按 key 升序排好的打包数组
     */
    fun packedMerges(vocab: Map<String, Int>): LongArray {
        skipSpaces()
        if (peek() != '[') return LongArray(0)
        pos++

        var capacity = 1 shl 16
        var packed = LongArray(capacity)
        var count = 0
        var rank = 0

        while (true) {
            skipSpaces()
            if (peek() == ']') break
            if (peek() != '"') break
            val rule = readString()
            if (rule.isNotEmpty()) {
                // SP-BPE 的 piece 里永远不会出现真正的空格（空格一律写成 ▁），
                // 所以「第一个空格」就是两个操作数的分隔符，不存在歧义
                val split = rule.indexOf(' ')
                if (split > 0 && split < rule.length - 1) {
                    val left = vocab[rule.substring(0, split)]
                    val right = vocab[rule.substring(split + 1)]
                    if (left != null && right != null) {
                        if (count == capacity) {
                            capacity = capacity shl 1
                            packed = packed.copyOf(capacity)
                        }
                        val pair = (left.toLong() shl ID_BITS) or right.toLong()
                        packed[count] = (pair shl RANK_BITS) or rank.toLong()
                        count++
                    }
                }
                rank++
            }
            skipSpaces()
            when (peek()) {
                ',' -> pos++
                else -> break
            }
        }

        val trimmed = packed.copyOf(count)
        java.util.Arrays.sort(trimmed)
        return trimmed
    }

    companion object {
        /** 每个 id 占的位数（2^18 = 26.2 万 > NLLB 的 25.6 万词表） */
        const val ID_BITS = 18

        /** 优先级占的位数（2^20 = 104.8 万 > NLLB 的 51.4 万 merge） */
        const val RANK_BITS = 20

        private const val RANK_MASK = (1L shl RANK_BITS) - 1L

        /** 在打包数组里二分查这对 piece 的优先级；没有这条规则返回 -1 */
        fun rankOf(packed: LongArray, left: Int, right: Int): Int {
            if (packed.isEmpty()) return -1
            val target = (left.toLong() shl ID_BITS) or right.toLong()
            var low = 0
            var high = packed.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val key = packed[mid] ushr RANK_BITS
                when {
                    key < target -> low = mid + 1
                    key > target -> high = mid - 1
                    else -> return (packed[mid] and RANK_MASK).toInt()
                }
            }
            return -1
        }
    }
}
