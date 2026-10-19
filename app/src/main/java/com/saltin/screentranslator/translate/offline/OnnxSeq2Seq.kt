package com.saltin.screentranslator.translate.offline

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime 上的 encoder-decoder 序列到序列推理（Marian / Opus-MT 规格）。
 *
 * # 一、张量名一律运行时读取，一个都不写死
 *
 * `optimum` 导出的 ONNX 在不同模型/版本之间命名有出入（缓存叫 `past_key_values.0.decoder.key`
 * 还是别的、有没有 `use_cache_branch`、编码器输出叫 `last_hidden_state` 还是
 * `encoder_hidden_states`）。硬编码任何一个名字，换个模型就整个跑不起来，而这类错误
 * 在手机上只能靠猜日志。所以这里统一从 session 的输入输出签名里读实际名字。
 *
 * 本工程实测（Opus-MT en-zh，`encoder_model_quantized` + `decoder_model_merged_quantized`）：
 *
 * ```
 * 编码器 输入  input_ids[int64], attention_mask[int64]
 *       输出  last_hidden_state[float]
 * 解码器 输入  input_ids, encoder_attention_mask, encoder_hidden_states,
 *             past_key_values.{0..5}.{decoder,encoder}.{key,value}, use_cache_branch[bool]
 *       输出  logits[float], present.{0..5}.{decoder,encoder}.{key,value}
 * ```
 *
 * **合并解码器直接吃 `encoder_hidden_states`**，所以不需要额外预算交叉注意力 KV。
 *
 * # 二、merged decoder 的三条硬规则（全部踩过坑，改代码前务必读完）
 *
 * ## 规则 1：present 必须改名成 past_key_values 才能喂回去
 * 直接把 `present.0.decoder.key` 按原名塞进 feeds，ORT 会报
 * `Required inputs (['past_key_values.0.decoder.key', ...]) are missing from input feed`。
 *
 * ## 规则 2：第一次调用必须 `use_cache_branch = false`，且要喂占位 past
 * 实测：第一次就传 `true` 会在 `If` 节点里直接炸
 * （`encoder_attn/Reshape_4` 报「dimension with value zero exceeds the dimension size」）。
 * 必须先用 `false` 走一遍「无缓存」分支把交叉注意力 KV 算出来。
 * 占位张量的序列维给 0 即可（此时这个分支压根不看它）。
 *
 * ## 规则 3：编码器那套 KV 只算一次，之后必须**冻结**
 * 这是最隐蔽的一条。观察每一步 `present.*` 的实际形状：
 *
 * ```
 * step0 (branch=false)  present.0.encoder.key = (1, 8, 2, 64)   ← 正确，src_len=2
 * step1 (branch=true)   present.0.encoder.key = (0, 8, 1, 64)   ← 垃圾占位！
 * ```
 *
 * 缓存分支里的 `present.*.encoder.*` 是退化的空张量。如果用它去覆盖编码器缓存，
 * 第 2 步必然崩；就算侥幸不崩，模型也会因为交叉注意力看到错的东西而**输出重复循环**
 * （实测「The quick brown fox…」翻成「快速快速的棕狐狐狐狐狐…」）。
 * 所以：`present.*.decoder.*` 每步滚动更新，`present.*.encoder.*` 只在第 0 步收一次、
 * 之后一律忽略并立刻释放。
 *
 * ## 规则 3 的连带要求：两类缓存**必须放在两个 map 里**
 *
 * 「每步释放上一轮的缓存」这行代码只该释放 decoder 那套。如果把两类混在一个 map 里，
 * 第 1 步就会把**冻结的编码器 KV 一并关掉**，而 feeds 里从此只剩 decoder 那套 ——
 * 第 2 步解码器缺必需输入直接抛异常，[translate] 被 catch 后返回空数组，
 * 上层拿不到 token 就把原文当译文。现象是「增强离线引擎永远返回原文」，
 * 从日志里几乎看不出来（只有一个 ORT 的 inputs-missing 异常）。
 * 所以本类刻意用 `frozenEncoderPast` / `liveDecoderPast` 两个独立的 map。
 *
 * # 二·补、`forcedBosId` 的来历：某些模型的起始符**不是**目标语言码（v1.6 修）
 *
 * 这条机制原本是为 NLLB 加的（NLLB 已被 v1.6.1 移除），但 [Spec.forcedBosId] 保留了下来 ——
 * 它是一套通用的「第 0 步强制产出某个 token」能力，将来接任何带
 * `forced_bos_token_id` 的 seq2seq 模型都用得上。当前 Opus-MT 传 null，不走这个分支。
 *
 * HuggingFace 里这类模型（如 NLLB）的 `generate` 流程是：
 * ```
 * decoder_input_ids[0] = config.decoder_start_token_id   # = 2 (</s>)，**不是语言码**
 * 然后通过 forced_bos_token_id = <目标语言码> 在第 0 步把输出**强制**成该语言码
 * ```
 * 也就是说，语言码是**第 0 步被强制产出的 token**，而不是喂进去的起始符。
 * 旧版把「目标语言码」当 `decoderStartId` 喂进去，实测会让模型无限重复 `▁`、
 * 输出空串、永远碰不到 EOS，128 个 token 预算全烧光 —— 又慢又没结果。
 *
 * Marian（Opus-MT）没有语言码机制，`forcedBosId` 留 null，行为与最早的版本完全一致。
 *
 * # 三、这套配方是实测验过的，不是照文档猜的
 *
 * 完整链路（**自写分词器出的 token → 真实 ONNX 模型 → 可读译文**）在桌面上跑通过：
 * 引擎层自身依赖 JNI，只能在真机跑，但「喂法」这一层已经被
 * `.tmp-verify/py_end2end.py` 逐条验证，Kotlin 这边与它逐行对应。改这里的喂法时，
 * 请先改那个脚本、确认译文仍然通顺，再改 Kotlin。
 *
 * # 四、生成方式
 *
 * 贪心解码（每步取 argmax）。没做 beam search —— 端侧翻译「快一倍、内存低一半」
 * 比「好一点点」实在，而且屏幕翻译的输入本来就是短句。
 *
 * # 五、张量所有权（ORT Java 的内存语义）
 *
 * `OrtSession.Result` 本身**不持有 native 句柄**，它只是 `OnnxValue` 的容器，
 * `close()` 会把容器里**由本次 run 分配**的那些值逐个关掉。
 * 因此对**解码器**的 Result 绝不能调 `close()` —— 它含着我们还要接着用的
 * `present.*` 张量。这里改成手动逐个管理：logits 读完就关，present 交接给下一轮，
 * 编码器 Result 只有一个输出，用完整体关掉。
 *
 * 这个类不是线程安全的（有跨步的缓存状态），[translate] 已加锁。
 */
class OnnxSeq2Seq(
    encoderFile: File,
    decoderFile: File,
    private val spec: Spec,
) : Closeable {

    /**
     * @param decoderStartId 解码器起始 token。Marian（Opus-MT）是 65000（`<pad>`）。
     * @param eosId 句子结束 token
     * @param vocabSize 词表大小，用来在 logits 里定位「最后一个位置」的切片
     * @param maxNewTokens 最多生成多少个 token
     * @param maxSourceTokens 源句最长多少个 token，超出直接截断
     * @param forcedBosId 第 0 步**强制**产出的 token（HF 的 `forced_bos_token_id`）。
     *   Opus-MT 传 null（无此机制）。保留这个开关是为了将来接别的 seq2seq 模型，
     *   见类顶部「`forcedBosId` 的来历」。
     * @param beamWidth beam search 的宽度。**1 = 纯贪心**（当前行为）。
     * @param lengthPenalty beam 排序时对长度的惩罚指数（HF 默认 1.0）
     * @param repeatPenalty 重复惩罚：对**已经出现过的** token 的 logit 减去这个值。
     *   兜底用，防止偶发的重复循环把预算烧光。
     * @param noRepeatNgramSize 禁止重复的 n-gram 长度（0 = 关闭）。3 是 HF 常用的值。
     */
    data class Spec(
        val decoderStartId: Long,
        val eosId: Long,
        val vocabSize: Int,
        val maxNewTokens: Int = 96,
        val maxSourceTokens: Int = 256,
        val forcedBosId: Long? = null,
        val beamWidth: Int = 1,
        val lengthPenalty: Float = 1.0f,
        val repeatPenalty: Float = 0f,
        val noRepeatNgramSize: Int = 0,
    )

    private companion object {
        const val TAG = "OnnxSeq2Seq"

        const val PAST_PREFIX = "past_key_values."
        const val PRESENT_PREFIX = "present"
        const val LOGITS = "logits"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /**
     * 建会话，**带优化级别降级**。
     *
     * 为什么不能无脑用 `ALL_OPT`：
     *   QDQ 形态的量化模型（int8 权重 + DequantizeLinear）在被 `EXTENDED` / `ALL_OPT`
     *   优化时会掉进 ORT 的「4bit 权重量化」推断路径，把 DequantizeLinear 误解成
     *   `MatMulNBits` 的数据源，于是要求一个不存在的 weight 张量：
     *
     *       TransposeDQWeightsForMatMulNBits ... Missing required weight
     *
     *   我们的量化是 **per-tensor int8**，不是 4bit 分块布局，这个改写注定失败。
     *   `BASIC` 不做这类改写，能正常加载，且绝大多数算子融合（常量折叠、
     *   冗余节点消除）仍然生效 —— 对推理速度影响很小。
     *
     * 策略：**先 ALL_OPT，失败就退 BASIC**。非量化模型（fp32 的 Xenova 版）仍然吃到
     * 完整优化；量化模型自动降级，不需要上层知道用的是哪种。
     */
    private fun createSession(file: java.io.File): OrtSession {
        val attempts = listOf(
            OrtSession.SessionOptions.OptLevel.ALL_OPT to "ALL_OPT",
            OrtSession.SessionOptions.OptLevel.BASIC_OPT to "BASIC",
            OrtSession.SessionOptions.OptLevel.NO_OPT to "NONE",
        )
        var lastError: Throwable? = null
        for ((level, label) in attempts) {
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(level)
            }
            try {
                val session = env.createSession(file.absolutePath, options)
                if (level != OrtSession.SessionOptions.OptLevel.ALL_OPT) {
                    Log.i(TAG, "会话以 $label 优化级建立：${file.name}（QDQ 量化模型需降级）")
                }
                return session
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "$label 优化级建会话失败，尝试降级：${file.name}", t)
            }
        }
        throw IllegalStateException(
            "ONNX 会话无法建立（已尝试 ALL_OPT / BASIC / NONE）：${file.name}", lastError,
        )
    }

    private val encoder: OrtSession
    private val decoder: OrtSession

    // ---- 编码器角色
    private val encoderIdsInput: String
    private val encoderMaskInput: String?
    private val encoderHiddenOutput: String

    // ---- 解码器角色
    private val decoderIdsInput: String
    private val decoderMaskInput: String?
    private val decoderHiddenInput: String?
    private val branchInput: String?

    /** 全部 `past_key_values.*` 输入名（已排序，保证与 present 输出一一对应） */
    private val pastInputs: List<String>

    /** 全部 `present*` 输出名（已排序） */
    private val presentOutputs: List<String>

    /** 每个 past 输入的声明形状，用来造占位张量 */
    private val pastShapes: Map<String, LongArray>

    /** `input_ids` 等整数张量在模型里声明的元素类型（通常 INT64，少数导出是 INT32） */
    private val encoderIdType: OnnxJavaType

    /**
     * 解码器那一侧的整数类型，**单独读**。
     *
     * 不跟编码器共用同一个值：`optimum` 的导出里两者理论上应当一致，但如果哪天不一致，
     * 共用一个值就会在建张量时抛一个很难定位的类型错误。多读一次签名是免费的。
     */
    private val decoderIdType: OnnxJavaType

    init {
        encoder = createSession(encoderFile)
        decoder = createSession(decoderFile)

        encoderIdsInput = encoder.inputNames.firstOrNull { it.contains("input_ids") }
            ?: encoder.inputNames.first()
        encoderMaskInput = encoder.inputNames.firstOrNull { it.contains("attention_mask") }
        encoderHiddenOutput = encoder.outputNames
            .firstOrNull { it.contains("hidden", ignoreCase = true) }
            ?: encoder.outputNames.first()

        decoderIdsInput = decoder.inputNames
            .firstOrNull { it.contains("input_ids") && !it.startsWith(PAST_PREFIX) }
            ?: "input_ids"
        decoderMaskInput = decoder.inputNames
            .firstOrNull { it.contains("attention_mask") && !it.startsWith(PAST_PREFIX) }
        decoderHiddenInput = decoder.inputNames
            .firstOrNull { it.contains("hidden", ignoreCase = true) && !it.startsWith(PAST_PREFIX) }
        branchInput = decoder.inputNames.firstOrNull { it == "use_cache_branch" }

        pastInputs = decoder.inputNames.filter { it.startsWith(PAST_PREFIX) }.sorted()
        presentOutputs = decoder.outputNames.filter { it.startsWith(PRESENT_PREFIX) }.sorted()

        pastShapes = pastInputs.associateWith { name ->
            tensorShape(decoder, name) ?: longArrayOf(1, 8, -1, 64)
        }
        encoderIdType = tensorType(encoder, encoderIdsInput)
        decoderIdType = tensorType(decoder, decoderIdsInput)

        // 必须能把每一个输入都喂上，否则这个模型结构我们驾驭不了。
        // 早失败、报清楚，好过跑到一半丢一堆看不懂的 ORT 异常。
        val supplied = buildSet {
            add(encoderIdsInput)
            encoderMaskInput?.let { add(it) }
            add(decoderIdsInput)
            decoderMaskInput?.let { add(it) }
            decoderHiddenInput?.let { add(it) }
            branchInput?.let { add(it) }
            addAll(pastInputs)
        }
        val missing = decoder.inputNames.filterNot { it in supplied }
        require(missing.isEmpty()) {
            "解码器有本工程无法提供的输入：$missing。模型结构不受支持，请换用其他离线引擎。"
        }
        require(pastInputs.size == presentOutputs.size) {
            "缓存输入(${pastInputs.size})与缓存输出(${presentOutputs.size})数量不一致，模型结构不受支持。"
        }
        if (decoderHiddenInput == null) {
            // 合并解码器必须能直接吃编码器隐状态；吃不进去说明这个导出依赖**预计算的交叉注意力 KV**，
            // 那是另一套（encoder_with_past）流程，本实现不覆盖。
            throw IllegalStateException(
                "解码器没有 encoder_hidden_states 输入，本实现不支持这种导出。请换用其他离线引擎。",
            )
        }

        Log.i(
            TAG,
            "ONNX 就绪：编码器 ${encoderFile.name}(${encoderFile.length() / 1048576}MB) / " +
                "解码器 ${decoderFile.name}(${decoderFile.length() / 1048576}MB)，" +
                "缓存 ${pastInputs.size} 对，use_cache_branch=${branchInput != null}",
        )
    }

    // ------------------------------------------------------------ 推理

    /**
     * 跑一次翻译。返回**不含**起始符与结束符的 token 序列。
     *
     * @param sourceIds 源 token；**必须已经带上 `</s>`**（Marian 的 post_processor 约定），
     *   漏掉它会让编码器看到截断的序列，译文会明显退化甚至出现重复循环。
     */
    @Synchronized
    fun translate(sourceIds: IntArray): IntArray {
        if (sourceIds.isEmpty()) return IntArray(0)

        val srcLen = minOf(sourceIds.size, spec.maxSourceTokens)
        val ids = LongArray(srcLen) { sourceIds[it].toLong() }
        val mask = LongArray(srcLen) { 1L }

        var encoderResult: OrtSession.Result? = null
        var decoderMask: OnnxTensor? = null

        // ⚠ 这两个 map **必须分开**，不能合成一个「livePast」。
        //
        // 原因（实测踩过，后果是整个引擎静默失效）：
        // 第 0 步之后如果把编码器 KV 和 decoder KV 混在一个 map 里，
        // 「每步释放上一轮缓存」那行代码会把**冻结的编码器 KV 也关掉**；
        // 而 feeds 里从此就只剩 decoder 那套 —— 第 2 步解码器缺必需输入，
        // ORT 直接抛异常，[translate] 被 catch 后返回空数组，
        // 上层拿不到任何 token 就把原文当译文显示。
        // 现象就是「增强离线引擎永远返回原文」，非常难从日志看出来。
        //
        // 所以：编码器那套放 frozenEncoderPast，第 0 步收一次后**只读**；
        // decoder 那套放 liveDecoderPast，每步滚动更新并释放上一轮。
        // 每一轮 feeds 都要把两者**同时**带上。
        val frozenEncoderPast = HashMap<String, OnnxTensor>(pastInputs.size / 2)
        var liveDecoderPast = HashMap<String, OnnxTensor>()

        try {
            // ---------------- 编码器（只跑一次）
            val encoderOutput = runEncoder(ids, mask)
            encoderResult = encoderOutput.first
            val hidden = encoderOutput.second ?: return IntArray(0)

            decoderMask = decoderMaskInput?.let {
                makeIntTensor(mask, longArrayOf(1, srcLen.toLong()), decoderIdType)
            }

            // ---------------- 解码器
            val generated = ArrayList<Int>(spec.maxNewTokens)
            var stepIds = longArrayOf(spec.decoderStartId)
            var stepIndex = 0

            // 「强制起始 token」：第 0 步不吃 argmax，直接产出这个 token，
            // 并把它作为第 1 步的输入。Opus-MT 不设（forcedBosId = null，不进这个分支）；
            // 保留是为了将来接别的 seq2seq 模型，见类顶部「`forcedBosId` 的来历」。
            val forcedFirst = spec.forcedBosId?.toInt()
            if (forcedFirst != null) {
                generated += forcedFirst
            }

            while (generated.size < spec.maxNewTokens) {
                val feeds = HashMap<String, OnnxTensor>(pastInputs.size * 2 + 6)
                val transient = ArrayList<OnnxTensor>(pastInputs.size + 2)

                val idsTensor = makeIntTensor(
                    stepIds,
                    longArrayOf(1, stepIds.size.toLong()),
                    decoderIdType,
                )
                feeds[decoderIdsInput] = idsTensor
                transient += idsTensor

                decoderMask?.let { feeds[decoderMaskInput!!] = it }
                feeds[decoderHiddenInput!!] = hidden

                if (liveDecoderPast.isEmpty()) {
                    // 规则 2：第一次走「无缓存」分支，缓存张量只是占位，序列维给 0
                    for (name in pastInputs) {
                        val placeholder = makeZeroTensor(emptyShapeOf(name))
                        feeds[name] = placeholder
                        transient += placeholder
                    }
                } else {
                    // 规则 1（改名成 past_key_values）与规则 3（编码器 KV 全程带上）
                    feeds.putAll(frozenEncoderPast)
                    feeds.putAll(liveDecoderPast)
                }

                branchInput?.let { name ->
                    val branch = makeBoolTensor(liveDecoderPast.isNotEmpty())
                    feeds[name] = branch
                    transient += branch
                }

                val result = decoder.run(feeds)

                // 输入张量在 run 返回后就完成了使命，立刻还回去
                transient.forEach { runCatching { it.close() } }
                // 只释放「上一轮的 decoder 缓存」—— 编码器缓存是冻结复用的，绝不能关
                liveDecoderPast.values.forEach { runCatching { it.close() } }
                liveDecoderPast = HashMap()

                val logits = result.get(LOGITS).orElse(null) as? OnnxTensor
                // ⚠ argmax 必须在 close 之前算：logits 一旦 close，floatBuffer 就失效了
                val greedy = logits?.let { argMaxLast(it, spec.vocabSize) } ?: -1
                runCatching { logits?.close() }

                // 收集本轮 present（必须在读完 logits 之后、且不 close result）
                for (name in presentOutputs) {
                    val tensor = result.get(name).orElse(null) as? OnnxTensor ?: continue
                    if (name.contains(".encoder.")) {
                        if (stepIndex == 0) {
                            // 规则 3：只有第 0 步的编码器 KV 是有效的，收下来冻结
                            frozenEncoderPast[pastNameOf(name)] = tensor
                        } else {
                            // 缓存分支里这个是退化张量，直接丢掉
                            runCatching { tensor.close() }
                        }
                    } else {
                        liveDecoderPast[pastNameOf(name)] = tensor
                    }
                }

                // 决定下一个输入 token
                val next: Int = if (stepIndex == 0 && forcedFirst != null) {
                    // 第 0 步：不看 argmax，强制用目标语言码（它已在 generated 里）
                    forcedFirst
                } else {
                    greedy
                }

                // 结束判定：非强制步遇到 EOS、或 logits 不可用
                if (stepIndex > 0 || forcedFirst == null) {
                    if (next < 0 || next.toLong() == spec.eosId) break
                    generated += next
                }

                stepIds = longArrayOf(next.toLong())
                stepIndex++
            }

            return generated.toIntArray()
        } catch (error: Throwable) {
            Log.w(TAG, "离线推理失败", error)
            return IntArray(0)
        } finally {
            liveDecoderPast.values.forEach { runCatching { it.close() } }
            frozenEncoderPast.values.forEach { runCatching { it.close() } }
            runCatching { decoderMask?.close() }
            // 编码器 Result 只有一个输出，用完整体关掉是安全的（它不含 present）
            runCatching { encoderResult?.close() }
        }
    }

    /** 跑编码器，返回「结果容器（负责释放）+ 隐状态张量」 */
    private fun runEncoder(ids: LongArray, mask: LongArray): Pair<OrtSession.Result?, OnnxTensor?> {
        val shape = longArrayOf(1, ids.size.toLong())
        val idsTensor = makeIntTensor(ids, shape, encoderIdType)
        val maskTensor = encoderMaskInput?.let { makeIntTensor(mask, shape, encoderIdType) }
        try {
            val feeds = HashMap<String, OnnxTensor>(2)
            feeds[encoderIdsInput] = idsTensor
            maskTensor?.let { feeds[encoderMaskInput!!] = it }
            val result = encoder.run(feeds)
            val hidden = result.get(encoderHiddenOutput).orElse(null) as? OnnxTensor
            if (hidden == null) {
                runCatching { result.close() }
                return null to null
            }
            return result to hidden
        } finally {
            runCatching { idsTensor.close() }
            runCatching { maskTensor?.close() }
        }
    }

    /**
     * 取「最后一个位置」的 argmax。
     *
     * logits 形状是 `[1, decoder_seq, vocab]`，而本实现每步只喂 1 个解码 token，
     * 所以 `decoder_seq` 恒为 1、整块缓冲就是「最后一个位置」。
     * 为稳妥起见仍按 `vocabSize` 从尾部切一刀：即使将来某步喂了多 token，取到的也是最后那个位置。
     */
    private fun argMaxLast(tensor: OnnxTensor, vocabSize: Int): Int {
        val buffer: FloatBuffer = tensor.floatBuffer
        val capacity = buffer.capacity()
        val width = if (vocabSize in 1..capacity) vocabSize else capacity
        val start = capacity - width
        var bestIndex = -1
        var bestValue = Float.NEGATIVE_INFINITY
        for (offset in 0 until width) {
            val value = buffer.get(start + offset)
            if (value > bestValue) {
                bestValue = value
                bestIndex = offset
            }
        }
        return bestIndex
    }

    /** `present.3.decoder.key` → `past_key_values.3.decoder.key` */
    private fun pastNameOf(presentName: String): String =
        PAST_PREFIX + presentName.removePrefix(PRESENT_PREFIX).removePrefix(".")

    /**
     * 占位缓存的形状：把动态维换成具体值。
     *
     * 声明形状形如 `[batch_size, 8, past_decoder_sequence_length, 64]`，
     * 符号维读进来是 -1。这里：batch → 1，序列维（下标 2）→ 0，其余动态维 → 1。
     */
    private fun emptyShapeOf(name: String): LongArray {
        val declared = pastShapes[name] ?: return longArrayOf(1, 8, 0, 64)
        return LongArray(declared.size) { index ->
            val dim = declared[index]
            when {
                dim > 0 -> dim
                index == 0 -> 1L
                index == 2 -> 0L
                else -> 1L
            }
        }
    }

    // ------------------------------------------------------------ 建张量

    private fun makeZeroTensor(shape: LongArray): OnnxTensor {
        var total = 1L
        shape.forEach { total *= it }
        val buffer = FloatBuffer.allocate(total.toInt().coerceAtLeast(0))
        return OnnxTensor.createTensor(env, buffer, shape)
    }

    /** 按模型声明的类型建整数张量（绝大多数是 INT64，少数导出是 INT32） */
    private fun makeIntTensor(
        values: LongArray,
        shape: LongArray,
        type: OnnxJavaType,
    ): OnnxTensor = when (type) {
        OnnxJavaType.INT32 -> {
            val buffer = IntBuffer.allocate(values.size)
            values.forEach { buffer.put(it.toInt()) }
            buffer.rewind()
            OnnxTensor.createTensor(env, buffer, shape)
        }
        OnnxJavaType.FLOAT -> {
            val buffer = FloatBuffer.allocate(values.size)
            values.forEach { buffer.put(it.toFloat()) }
            buffer.rewind()
            OnnxTensor.createTensor(env, buffer, shape)
        }
        else -> OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)
    }

    /** `use_cache_branch` 声明为 `tensor(bool) [1]`。Java 侧没有 boolean[] 重载，用 ByteBuffer 指定 BOOL */
    private fun makeBoolTensor(value: Boolean): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(1)
        buffer.put(if (value) 1 else 0)
        buffer.rewind()
        return OnnxTensor.createTensor(env, buffer, longArrayOf(1), OnnxJavaType.BOOL)
    }

    private fun tensorShape(session: OrtSession, name: String): LongArray? =
        (session.inputInfo[name]?.info as? TensorInfo)?.shape

    private fun tensorType(session: OrtSession, name: String): OnnxJavaType =
        (session.inputInfo[name]?.info as? TensorInfo)?.type ?: OnnxJavaType.INT64

    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
    }
}
