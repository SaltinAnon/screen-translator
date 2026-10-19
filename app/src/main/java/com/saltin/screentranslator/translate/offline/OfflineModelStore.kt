package com.saltin.screentranslator.translate.offline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** 下载进度，直接喂给设置页的进度条 */
data class PackProgress(
    val packId: String,
    val label: String,
    val fileIndex: Int,
    val fileCount: Int,
    val currentFile: String,
    val bytesDone: Long,
    val bytesTotal: Long,
) {
    val fraction: Float
        get() = if (bytesTotal > 0L) {
            (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val doneMb: Int get() = (bytesDone / 1048576L).toInt()
    val totalMb: Int get() = (bytesTotal / 1048576L).toInt()
}

/**
 * 增强离线模型的本地仓库：下载、续传、校验、删除、占用统计。
 *
 * ## 四个刻意的设计（每一条都是踩过坑才有的）
 *
 * 1. **校验基准必须是服务端的 `Content-Length`，不是目录里的估算值。**
 *    v1.5 拿 `ModelFile.bytes`（四舍五入的估算值）去判「下完了没有」，结果
 *    真实 52899742 字节的 Opus 编码器被估算值 52844544 判成「不完整」，
 *    两个模型都卡在「下到一半报错」。现在下载前先探测真实大小，下完照它核对。
 * 2. **下载成功后写 `manifest.json` 记录真实字节数。** 这样 [isReady] 不需要联网
 *    也能精确判断，而且上游哪天重新导出模型（大小变了）也不会把老文件误判成坏的。
 * 3. **写到 `.part` 再改名。** 模型几百 MB，中途断网/退出留下半个文件的话，
 *    下次会被当成「已下载」直接加载 —— ONNX 加载失败还是小事，用户看到的是
 *    「模型下好了却不能用」。所以只有**字节数完全对上**才算完成。
 * 4. **每个文件自带重试与续传。** 单个语言对约 115MB，手机网络上一口气下完不现实，
 *    而任何一次抖动都会让 HTTP 流断掉。旧版直接判定失败，用户看到的是
 *    「下到一半就报错」。现在同一个文件最多重试 [MAX_ATTEMPTS] 次，
 *    每次用 `Range` 从 `.part` 的断点接着下。
 *
 * 顺带一提：`huggingface.co` 的下载会 302 到带签名、**1 小时后过期**的 CDN 地址。
 * 续传时重新发一次请求会重新拿到新的签名地址，所以长时间断点续传反而更稳。
 */
object OfflineModelStore {

    private const val TAG = "OfflineModelStore"
    private const val ROOT_DIR = "offline-models"
    private const val PART_SUFFIX = ".part"

    /**
     * 单个文件最多重试几次。
     *
     * 5 次是权衡：手机蜂窝网络下 400MB 的文件偶尔断一两次很常见，
     * 但真遇到「下载源本身不可用」时，5 次 × 长超时已经足够让用户意识到要换源，
     * 再多就只是干等着了。
     */
    private const val MAX_ATTEMPTS = 5

    /** 重试前的退避基数，第 n 次退避 n × 该值 */
    private const val RETRY_BACKOFF_MS = 1200L

    /** 下载用客户端：模型服务器慢，超时给足 */
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 空间留一点余量：算 1.05 倍，避免刚好卡在边界上 */
    private const val SPACE_HEADROOM = 1.05

    /**
     * 请求头里必须带 User-Agent。
     *
     * 实测（2026-10-03）`hf-mirror.com` 对**完全没有 UA** 的请求直接返回 403 ——
     * OkHttp 默认会带 `okhttp/4.x`，本来就能过；显式写一个是为了将来换 HTTP 客户端
     * 时不会掉进这个坑。这个 403 看起来像「下载源挂了」或「仓库变成私有的」，
     * 实际原因跟 URL 一点关系都没有，极难定位，所以干脆钉死在这里。
     */
    private const val USER_AGENT = "ScreenTranslator/1.5.1 (Android)"

    private fun requestBuilder(url: String): Request.Builder =
        Request.Builder().url(url).header("User-Agent", USER_AGENT)

    sealed interface DownloadResult {
        data class Success(val packs: List<ModelPack>) : DownloadResult
        data class Failed(val reason: String) : DownloadResult
    }

    // ------------------------------------------------------------ 路径与状态

    private fun rootDir(context: Context): File =
        File(context.filesDir, ROOT_DIR).apply { if (!exists()) mkdirs() }

    fun packDir(context: Context, packId: String): File = File(rootDir(context), packId)

    /** 某个文件下载到本地后的路径 */
    fun fileOf(context: Context, pack: ModelPack, file: ModelFile): File =
        File(packDir(context, pack.id), file.path.substringAfterLast('/'))

    /** 上次下到一半留下的临时文件 */
    private fun partOf(context: Context, pack: ModelPack, file: ModelFile): File {
        val target = fileOf(context, pack, file)
        return File(target.parentFile, target.name + PART_SUFFIX)
    }

    private fun manifestFile(context: Context, pack: ModelPack): File =
        File(packDir(context, pack.id), OfflineModelCatalog.MANIFEST_NAME)

    /**
     * 读下载完成后写下的真实尺寸清单（文件名 → 字节数）。没有清单时返回空表。
     *
     * 清单缺失意味着「这些文件不是本版本下载器装的」：
     * 可能是 v1.5 及以前留下的、也可能是用户手动拷进去的，都不可信。
     */
    private fun readManifest(context: Context, pack: ModelPack): Map<String, Long> {
        val file = manifestFile(context, pack)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val json = JSONObject(file.readText())
            buildMap {
                json.keys().forEach { name ->
                    val value = json.optLong(name, -1L)
                    if (value > 0L) put(name, value)
                }
            }
        }.onFailure { Log.w(TAG, "读取模型清单失败", it) }.getOrDefault(emptyMap())
    }

    private fun writeManifest(context: Context, pack: ModelPack, sizes: Map<String, Long>) {
        runCatching {
            val json = JSONObject()
            sizes.forEach { (name, bytes) -> json.put(name, bytes) }
            manifestFile(context, pack).writeText(json.toString())
        }.onFailure { Log.w(TAG, "写入模型清单失败", it) }
    }

    /**
     * 这个包是不是已经完整下载好了。
     *
     * 判据是**每个文件都存在，且字节数与清单里的真实值一致** ——
     * 光看目录存在是不够的：下载中途退出会留下不完整的文件。
     *
     * 清单不存在时的兜底：拿估算值做 ±20% 的宽松比较。估算值本身是准的（误差 < 0.1%），
     * 所以完整的文件能通过，而下了一半的必然被拦下；通过时顺手把清单补上。
     */
    fun isReady(context: Context, pack: ModelPack): Boolean {
        val sizes = readManifest(context, pack)
        var allFine = true
        val discovered = HashMap<String, Long>()

        for (file in pack.files) {
            val local = fileOf(context, pack, file)
            if (!local.isFile || local.length() <= 0L) return false
            val recorded = sizes[local.name]
            if (recorded != null) {
                if (local.length() != recorded) return false
            } else {
                val low = (file.bytes * 0.8).toLong()
                val high = (file.bytes * 1.2).toLong()
                if (local.length() < low || local.length() > high) allFine = false
                discovered[local.name] = local.length()
            }
        }

        if (!allFine) return false
        // 没有清单但文件都对得上（例如从别处拷进来的）→ 补一份，省掉一次重下
        if (sizes.isEmpty() && discovered.isNotEmpty()) writeManifest(context, pack, discovered)
        return true
    }

    /**
     * 已经下好多少字节（含 `.part` 的断点部分）。
     *
     * 带上 `.part` 是关键：用户下了 300MB 断网重来，如果这里只数正式文件，
     * 界面会显示「还没下载」，等于把他刚下的 300MB 说没了。
     */
    fun presentBytes(context: Context, pack: ModelPack): Long =
        pack.files.sumOf { file ->
            val target = fileOf(context, pack, file)
            if (target.isFile) {
                target.length()
            } else {
                partOf(context, pack, file).let { if (it.isFile) it.length() else 0L }
            }
        }

    /** 所有增强模型占用的空间 */
    fun usedBytes(context: Context): Long {
        val root = rootDir(context)
        return root.walkBottomUp()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    fun remove(context: Context, packId: String): Boolean {
        val dir = packDir(context, packId)
        if (!dir.exists()) return true
        return runCatching { dir.deleteRecursively() }
            .onFailure { Log.w(TAG, "删除模型目录失败", it) }
            .getOrDefault(false)
    }

    fun clearAll(context: Context): Boolean {
        val root = rootDir(context)
        return runCatching { root.deleteRecursively() }
            .onFailure { Log.w(TAG, "清空模型目录失败", it) }
            .getOrDefault(false)
    }

    // ------------------------------------------------------------ 体积探测

    /** 一次形态探测的结果：真实字节数 / 是否可信 / 明确的失败原因 */
    private data class Probe(val bytes: Long, val exact: Boolean, val error: String? = null)

    /**
     * 问服务端这个文件到底多大。
     *
     * 先 `HEAD`（最省流量）；有些 CDN 对 HEAD 不友好，再退化成
     * `GET Range: bytes=0-0` —— 只拿 1 个字节，然后从 `Content-Range`
     * 的 `bytes 0-0/52899742` 里读总长度。两条都问不出来时就退回估算值，
     * 并把 [Probe.exact] 置 false（下载完成后改用下载到的字节数自校验）。
     */
    private fun probe(url: String, fallbackBytes: Long): Probe {
        // 1) HEAD
        try {
            http.newCall(requestBuilder(url).head().build()).execute().use { response ->
                val failure = describeHttpFailure(response.code)
                if (failure != null) return Probe(fallbackBytes, false, failure)
                val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
                if (length > 0L) return Probe(length, true)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "HEAD 探测失败，改用 Range 探测", t)
        }

        // 2) Range: bytes=0-0
        try {
            http.newCall(
                requestBuilder(url).header("Range", "bytes=0-0").build(),
            ).execute().use { response ->
                val failure = describeHttpFailure(response.code)
                if (failure != null) return Probe(fallbackBytes, false, failure)
                val total = response.header("Content-Range")
                    ?.substringAfterLast('/')?.trim()?.toLongOrNull()
                if (total != null && total > 0L) return Probe(total, true)
                val length = response.body?.contentLength() ?: -1L
                if (length > 0L) return Probe(length, true)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Range 探测失败", t)
        }

        return Probe(fallbackBytes, false)
    }

    /** 把 HTTP 状态码翻译成人话；不算失败时返回 null */
    private fun describeHttpFailure(code: Int): String? = when {
        code in 200..299 -> null
        code == 404 -> "下载源上没有这个模型文件（HTTP 404），仓库可能已经改名或删档。"
        code == 401 || code == 403 -> "下载源拒绝了访问（HTTP $code），这个源可能需要登录，换一个试试。"
        else -> "下载源返回 HTTP $code。"
    }

    // ------------------------------------------------------------ 下载

    private data class PlannedFile(
        val file: ModelFile,
        val url: String,
        val expected: Long,
        val exact: Boolean,
    )

    private data class Planned(val pack: ModelPack, val files: List<PlannedFile>) {
        val bytes: Long get() = files.sumOf { it.expected }
    }

    /**
     * 下载一组模型包（一条翻译路线可能是「直接一个」也可能是「经英语中转两个」）。
     *
     * 先把所有文件的体积探测出来，再逐个下载，进度按**全程总字节**给出，
     * 这样中转路线的进度条不会在第二个模型处跳回 0。
     *
     * @param onProgress 进度回调，从 IO 线程发起，界面侧自己切主线程
     */
    suspend fun downloadAll(
        context: Context,
        packs: List<ModelPack>,
        host: String,
        onProgress: (PackProgress) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (packs.isEmpty()) return@withContext DownloadResult.Failed("没有需要下载的模型包。")

        // ---------- 1) 探测真实体积 ----------
        val plan = ArrayList<Planned>(packs.size)
        for (pack in packs) {
            val manifest = readManifest(context, pack)
            val files = ArrayList<PlannedFile>(pack.files.size)
            for (file in pack.files) {
                val name = file.path.substringAfterLast('/')
                val url = OfflineModelCatalog.urlOf(host, pack.repo, file.path)
                val probe = probe(url, file.bytes)
                if (probe.error != null) {
                    return@withContext DownloadResult.Failed(
                        "「${pack.label}」的 $name：" +
                            probe.error + "下载源「$host」可以在下面换一个再试。",
                    )
                }
                // 该文件的「应有大小」按可信度取：服务端探测值 > 本地清单记录值 > 估算值。
                // 只有前两者算可信，估算值绝不拿来判「下完了没有」——
                // v1.5 就是拿估算值判的，于是两个模型都卡在「下到一半报错」。
                val recorded = manifest[name]
                val expected = when {
                    probe.exact -> probe.bytes
                    recorded != null -> recorded
                    else -> file.bytes
                }
                files += PlannedFile(file, url, expected, probe.exact || recorded != null)
            }
            plan += Planned(pack, files)
        }

        // ---------- 2) 空间检查 ----------
        val grandTotal = plan.sumOf { it.bytes }
        val remainingBytes = plan.sumOf { planned ->
            planned.files.sumOf { pf ->
                val done = resumePoint(context, planned.pack, pf)
                (pf.expected - done).coerceAtLeast(0L)
            }
        }
        val free = runCatching { rootDir(context).usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free < (remainingBytes * SPACE_HEADROOM).toLong() + 64L * 1048576L) {
            return@withContext DownloadResult.Failed(
                "手机可用空间不足：还需要约 ${remainingBytes / 1048576}MB，" +
                    "当前只剩 ${free / 1048576}MB。请先清理空间再试。",
            )
        }

        // ---------- 3) 逐个文件下载 ----------
        var globalBase = 0L
        val recorded = HashMap<String, MutableMap<String, Long>>()

        for (planned in plan) {
            val pack = planned.pack
            val sizes = recorded.getOrPut(pack.id) { HashMap() }

            for ((index, pf) in planned.files.withIndex()) {
                currentCoroutineContext().ensureActive()

                val fileName = pf.file.path.substringAfterLast('/')
                val target = fileOf(context, pack, pf.file)
                val part = partOf(context, pack, pf.file)

                // 已经完整下好（可能是上次中途断了、后来手动补齐的）→ 跳过
                if (target.isFile && target.length() == pf.expected) {
                    sizes[target.name] = target.length()
                    globalBase += pf.expected
                    report(onProgress, pack, index, fileName, globalBase, grandTotal)
                    continue
                }
                // 大小对不上（旧版残留 / 上游换了模型）→ 丢掉重下，避免拿半个文件去加载
                if (target.isFile) target.delete()

                target.parentFile?.mkdirs()

                // 上一次正好卡在「写完了文件」和「改名字」之间（比如忽然被系统杀掉）：
                // 字节数已经对得上，直接改名收工，不用再下一次 400MB。
                if (pf.exact && part.isFile && part.length() == pf.expected &&
                    part.renameTo(target)
                ) {
                    sizes[target.name] = target.length()
                    globalBase += pf.expected
                    report(onProgress, pack, index, fileName, globalBase, grandTotal)
                    continue
                }

                var lastError = "未知原因"
                var completed = false

                for (attempt in 1..MAX_ATTEMPTS) {
                    currentCoroutineContext().ensureActive()
                    val startAt = resumePoint(context, pack, pf)
                    val outcome = fetchInto(pf, part, startAt, onProgress, pack, index, fileName, globalBase, grandTotal)
                    when (outcome) {
                        is Fetch.Fatal -> return@withContext DownloadResult.Failed(outcome.reason)
                        is Fetch.Done -> {
                            completed = true
                            break
                        }
                        is Fetch.Retry -> {
                            lastError = outcome.reason
                            if (outcome.restart) part.delete()
                            Log.w(TAG, "第 $attempt/$MAX_ATTEMPTS 次下载 $fileName 失败：$lastError")
                            if (attempt < MAX_ATTEMPTS) delay(RETRY_BACKOFF_MS * attempt)
                        }
                    }
                }

                if (!completed) {
                    return@withContext DownloadResult.Failed(
                        "下载「$fileName」连续 $MAX_ATTEMPTS 次都没成功：$lastError。" +
                            "已经下好的部分会保留，网络恢复后重新点一次「继续下载」即可接着下。",
                    )
                }

                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    return@withContext DownloadResult.Failed(
                        "保存模型文件失败（可能是空间不足或权限问题），请检查后重试。",
                    )
                }
                sizes[target.name] = target.length()
                globalBase += pf.expected
                report(onProgress, pack, index, fileName, globalBase, grandTotal)
            }

            writeManifest(context, pack, sizes)
        }

        val notReady = packs.firstOrNull { !isReady(context, it) }
        if (notReady != null) {
            return@withContext DownloadResult.Failed(
                "「${notReady.label}」下载完成后校验没通过，请重试一次。",
            )
        }
        DownloadResult.Success(packs)
    }

    /** 单包下载的便捷入口 */
    suspend fun download(
        context: Context,
        pack: ModelPack,
        host: String,
        onProgress: (PackProgress) -> Unit = {},
    ): DownloadResult = downloadAll(context, listOf(pack), host, onProgress)

    private fun report(
        onProgress: (PackProgress) -> Unit,
        pack: ModelPack,
        index: Int,
        fileName: String,
        doneBytes: Long,
        grandTotal: Long,
    ) {
        onProgress(
            PackProgress(
                packId = pack.id,
                label = pack.label,
                fileIndex = index,
                fileCount = pack.files.size,
                currentFile = fileName,
                bytesDone = doneBytes,
                bytesTotal = grandTotal,
            ),
        )
    }

    /** 这个文件已经下了多少（正式文件优先，其次看 `.part` 断点） */
    private fun resumePoint(context: Context, pack: ModelPack, pf: PlannedFile): Long {
        val target = fileOf(context, pack, pf.file)
        if (target.isFile && target.length() == pf.expected) return pf.expected
        val part = partOf(context, pack, pf.file)
        if (!part.isFile) return 0L
        val length = part.length()
        return if (length in 0 until pf.expected) length else 0L
    }

    private sealed interface Fetch {
        /** 这个文件下完了 */
        data object Done : Fetch

        /**
         * 这一次失败，可以续传重试。
         *
         * [restart] 为 true 表示「`.part` 的内容已经不可信了」（比如断点位置越过了
         * 文件末尾、或者写出来的比应有的还长），下一次必须从 0 重来，
         * 否则会一直拿同一个坏断点去撞同一个错误。
         */
        data class Retry(val reason: String, val restart: Boolean = false) : Fetch

        /** 重试也没意义（比如 404），直接结束 */
        data class Fatal(val reason: String) : Fetch
    }

    /**
     * 把 [pf] 的文件内容拉到 [part] 里，从 [startAt] 字节处续传。
     *
     * 校验用**服务端给的长度**（探测到的精确值优先，其次是本次响应的 `Content-Length`），
     * 只有当两者都拿不到时才放弃校验 —— 那种情况下至少要求文件非空。
     */
    private suspend fun fetchInto(
        pf: PlannedFile,
        part: File,
        startAt: Long,
        onProgress: (PackProgress) -> Unit,
        pack: ModelPack,
        index: Int,
        fileName: String,
        globalBase: Long,
        grandTotal: Long,
    ): Fetch {
        val request = requestBuilder(pf.url)
            .apply { if (startAt > 0L) addHeader("Range", "bytes=$startAt-") }
            .build()

        try {
            http.newCall(request).execute().use { response ->
                // 416 = 断点位置已经越过文件末尾（多半是 .part 其实是完整的）。
                // 重试多少次都还是 416，必须把断点丢掉从 0 来。
                if (response.code == 416) {
                    return Fetch.Retry("断点位置已超出文件长度，将从 0 重新下载", restart = true)
                }
                if (!response.isSuccessful) {
                    val failure = describeHttpFailure(response.code)
                        ?: "下载源返回 HTTP ${response.code}"
                    // 404 / 403 重试多少次都一样，直接判死
                    return if (response.code == 404 || response.code == 401 || response.code == 403) {
                        Fetch.Fatal(failure)
                    } else {
                        Fetch.Retry(failure)
                    }
                }

                val body = response.body ?: return Fetch.Retry("响应内容为空")
                val responseLength = body.contentLength()
                // 服务端不支持 Range 时会回 200 + 全量，此时必须从头写，不能追加
                val appending = response.code == 206 && startAt > 0L
                val writeFrom = if (appending) startAt else 0L

                body.byteStream().use { input ->
                    RandomAccessFile(part, "rw").use { raf ->
                        if (!appending) raf.setLength(0L)
                        raf.seek(writeFrom)
                        val buffer = ByteArray(64 * 1024)
                        var written = writeFrom
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            raf.write(buffer, 0, read)
                            written += read
                            if (written - lastReport >= 256L * 1024L) {
                                lastReport = written
                                onProgress(
                                    PackProgress(
                                        packId = pack.id,
                                        label = pack.label,
                                        fileIndex = index,
                                        fileCount = pack.files.size,
                                        currentFile = fileName,
                                        bytesDone = globalBase + written,
                                        bytesTotal = grandTotal,
                                    ),
                                )
                            }
                        }
                    }
                }

                val expected = when {
                    pf.exact -> pf.expected
                    responseLength > 0L -> writeFrom + responseLength
                    else -> -1L
                }
                val actual = part.length()
                return when {
                    actual <= 0L -> Fetch.Retry("下载到 0 字节")
                    // 写出来的比应有的还长：断点已经不可信，从 0 重来
                    expected > 0L && actual > expected ->
                        Fetch.Retry("字节数多了（$actual / $expected）", restart = true)
                    expected > 0L && actual < expected ->
                        Fetch.Retry("字节数少了（$actual / $expected）")
                    else -> Fetch.Done
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            return Fetch.Retry(t.message ?: t::class.java.simpleName)
        }
    }
}
