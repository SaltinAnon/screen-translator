package com.saltin.screentranslator.translate.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * 自写分词器的回归测试：拿 HuggingFace **官方 `tokenizers` 库**的输出当标准答案，
 * 逐 token 比对。
 *
 * ## 为什么必须这么测
 *
 * 「手写一个分词器」是本工程风险最高的一块：它没有任何编译期检查，「能编译、翻出来是垃圾」
 * 是完全可能的（NLLB 那套 BPE 尤其容易错在 merge 优先级、Metaspace 边界、归一化上）。
 * 而分词错了译文必然错，且错得非常隐蔽（看起来像「模型不行」）。
 * 所以用真实模型 + 官方答案锁死。
 *
 * ## 需要真实模型文件
 *
 * `tokenizer.json` 有几十 MB，不适合进仓库。测试从模型目录读，找不到就**跳过**而不是失败：
 *
 * ```
 * ./gradlew testDebugUnitTest -Dst.models.dir=F:/some/models
 * ```
 *
 * 默认路径是 `<工程根>/.tmp-verify/models`（相对 app 模块目录就是 `../.tmp-verify/models`）。
 *
 * 标准答案文件由 `.tmp-verify/make_tokenizer_golden.py` 生成（依赖 Python 的 `tokenizers` 包），
 * 转换脚本是 `.tmp-verify/make_golden_txt.py`。
 */
class TokenizerGoldenTest {

    private companion object {
        /** 模型 id → tokenizer.json 相对路径 */
        val MODELS = linkedMapOf(
            "opus-mt-en-zh" to "opus-mt-en-zh/tokenizer.json",
            "nllb-200-distilled-600M" to "nllb-tokenizer/tokenizer.json",
        )

        const val GOLDEN_RESOURCE = "tokenizer-golden.txt"

        fun modelsRoot(): File {
            val override = System.getProperty("st.models.dir")
            if (!override.isNullOrBlank()) return File(override)
            // 单测的工作目录是 app 模块目录
            return File("../.tmp-verify/models")
        }
    }

    /** 读出标准答案：`=== modelId ===` 分节，每节若干 `base64(原文) TAB id,id,id` */
    private fun readGolden(): Map<String, List<Pair<String, IntArray>>> {
        val stream = javaClass.classLoader?.getResourceAsStream(GOLDEN_RESOURCE)
            ?: error("测试资源 $GOLDEN_RESOURCE 缺失")
        val sections = LinkedHashMap<String, MutableList<Pair<String, IntArray>>>()
        var current: MutableList<Pair<String, IntArray>>? = null
        stream.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
            val line = raw.trimEnd()
            when {
                line.isEmpty() || line.startsWith("#") -> Unit
                line.startsWith("=== ") -> {
                    val id = line.removePrefix("=== ").removeSuffix("===").trim()
                    current = sections.getOrPut(id) { ArrayList() }
                }
                else -> {
                    val tab = line.indexOf('\t')
                    if (tab > 0) {
                        val text = String(Base64.getDecoder().decode(line.substring(0, tab)), Charsets.UTF_8)
                        val ids = line.substring(tab + 1)
                            .split(',')
                            .filter { it.isNotBlank() }
                            .map { it.trim().toInt() }
                            .toIntArray()
                        current?.add(text to ids)
                    }
                }
            }
        }
        return sections
    }

    @Test
    fun `分词结果与官方 tokenizers 库逐 token 一致`() {
        val golden = readGolden()
        assertEquals("标准答案里应该有 2 个模型", 2, golden.size)

        var checked = 0
        var skipped = 0

        for ((modelId, cases) in golden) {
            val relative = MODELS[modelId]
            assertTrue("标准答案里出现了未登记模型：$modelId", relative != null)

            val file = File(modelsRoot(), relative!!)
            if (!file.isFile) {
                skipped += cases.size
                println("[跳过] $modelId —— 找不到 ${file.absolutePath}")
                continue
            }

            val failures = ArrayList<String>()

            val tokenizer = try {
                measureHeap("加载 $modelId") { OfflineTokenizers.load(file) }
            } catch (t: Throwable) {
                failures += "  !! $modelId 分词器加载失败：$t"
                assertTrue(failures.joinToString("\n"), false)
                continue
            }
            println("[核对] $modelId：词表 ${tokenizer.vocabSize}，eos=${tokenizer.eosId}")

            for ((text, expected) in cases) {
                val actual = tokenizer.encode(text, appendEos = false)
                if (!actual.contentEquals(expected)) {
                    failures += buildString {
                        append("  原文 ").append(text).append('\n')
                        append("    期望 ").append(expected.joinToString(","))
                        append(" (").append(tokenizer.describe(expected)).append(")\n")
                        append("    实际 ").append(actual.joinToString(","))
                        append(" (").append(tokenizer.describe(actual)).append(")")
                    }
                }
                checked++
            }

            assertTrue(
                "$modelId 有 ${failures.size}/${cases.size} 条用例对不上：\n" +
                    failures.joinToString("\n"),
                failures.isEmpty(),
            )
        }

        if (skipped > 0) {
            println("提示：$skipped 条用例因模型文件缺失被跳过（用 -Dst.models.dir=... 指定模型目录）")
        }
        assertTrue("一条用例都没跑起来，测试没有意义", checked > 0)
    }

    @Test
    fun `解码能还原出可读文本`() {
        val opus = File(modelsRoot(), MODELS.getValue("opus-mt-en-zh"))
        assumeTrue("找不到 Opus-MT 分词器，跳过", opus.isFile)

        val tokenizer = OfflineTokenizers.load(opus)
        // 编码 → 解码应当回到原句（大小写与空格保持）
        listOf(
            "Hello world",
            "The quick brown fox jumps over the lazy dog.",
            "Please don't touch that button.",
        ).forEach { text ->
            val roundTrip = tokenizer.decode(tokenizer.encode(text, appendEos = true))
            assertEquals("往返解码结果不一致：$text", text, roundTrip)
        }
    }

    @Test
    fun `encodeEos 会在末尾补上句子结束符`() {
        val opus = File(modelsRoot(), MODELS.getValue("opus-mt-en-zh"))
        assumeTrue("找不到 Opus-MT 分词器，跳过", opus.isFile)

        val tokenizer = OfflineTokenizers.load(opus)
        val plain = tokenizer.encode("Hello world")
        val withEos = tokenizer.encode("Hello world", appendEos = true)

        assertEquals("补 EOS 后应该正好多一个 token", plain.size + 1, withEos.size)
        assertEquals("末尾必须是 eos", tokenizer.eosId, withEos.last())
        assertTrue("前半段不应受影响", withEos.copyOf(plain.size).contentEquals(plain))
    }

    /** 把 id 序列翻成 piece，方便看失败原因 */
    private fun SubwordTokenizer.describe(ids: IntArray): String =
        ids.joinToString(" ") { if (isSpecial(it)) "<${pieceOf(it)}>" else pieceOf(it) }

    /**
     * 边加载边采样堆占用，报出**峰值**与**常驻**两个数。
     *
     * 为什么要专门测这个：安卓单应用堆上限通常只有 256~512MB，
     * 而 NLLB 的 `tokenizer.json` 有 16.5MB 文本 + 25.6 万词条 + 51.4 万条 merge 规则。
     * 如果峰值逼近上限，真机上会被系统直接杀掉 —— 那是一个极难定位的线上问题，
     * 所以在这个测试里就把数字量出来。
     */
    private fun <T> measureHeap(label: String, block: () -> T): T {
        val runtime = Runtime.getRuntime()
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        val peak = java.util.concurrent.atomic.AtomicLong(0L)

        val sampler = Thread {
            while (alive.get()) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                peak.updateAndGet { maxOf(it, used) }
                try {
                    Thread.sleep(4)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }
        sampler.isDaemon = true
        sampler.start()

        val result = block()

        alive.set(false)
        System.gc()
        Thread.sleep(60)
        val retained = runtime.totalMemory() - runtime.freeMemory()

        println(
            "[内存] $label：峰值 ${peak.get() / 1048576}MB / 常驻 ${retained / 1048576}MB " +
                "（堆上限 ${runtime.maxMemory() / 1048576}MB）",
        )
        return result
    }
}
