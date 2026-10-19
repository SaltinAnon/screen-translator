package com.saltin.screentranslator.translate

import android.graphics.Bitmap
import com.saltin.screentranslator.ocr.TextBlock

/**
 * 阶段三统一抽象。
 *
 * - [requiresFrameBitmap] = false 的引擎走 [translateBlocks]（端侧，纯文本）。
 * - [requiresFrameBitmap] = true  的引擎走 [translateFrame]（云端多模态，可直接吃 Bitmap）。
 */
interface TranslationEngine {

    /** 稳定标识，用于缓存与日志 */
    val id: String

    /** 展示名 */
    val displayName: String

    /** 是否需要原始帧 */
    val requiresFrameBitmap: Boolean

    /** 预热：端侧用来提前下载/加载模型，云端可以用来做一次连通性自检。 */
    suspend fun prepare(sourceLanguage: String, targetLanguage: String) = Unit

    /**
     * 纯文本翻译。[blocks] 已经过去重与清洗。
     * 返回结果的数量与顺序不保证与入参一致，**必须按 [TranslatedBlock.originalText] 回填**。
     *
     * 纯多模态引擎（只实现 [translateFrame]）可以不覆写本方法。
     */
    suspend fun translateBlocks(
        blocks: List<TextBlock>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<TranslatedBlock> = emptyList()

    /**
     * 多模态翻译：把整帧位图交给模型。
     *
     * @param hintBlocks 非空时表示「混合模式」：模型只负责按序号产出译文，
     *                   坐标复用 OCR 结果（比让模型自己定位更准、更省 token）。
     *                   为空时表示「纯多模态」：模型需要自己给出归一化坐标 (0~1)。
     * @param fallbackHints 纯多模态专用的**兜底锚点**，来自端侧 OCR，坐标是真实屏幕像素。
     *
     *   为什么需要它：纯多模态要求模型既看懂图、又按 `x/y/w/h` 回坐标，而相当多的看图模型
     *   （Qwen-VL 系列尤其典型）只肯回译文、不肯回坐标，于是整屏判定为「没有译文块」、
     *   一个字都不显示。有这批锚点之后，引擎可以在首选问法失败时**换一种问法重试一次**：
     *   把 OCR 找到的行带编号发给模型，坐标直接复用 —— 至少保证屏幕上有译文。
     */
    suspend fun translateFrame(
        frame: Bitmap,
        hintBlocks: List<TextBlock>,
        sourceLanguage: String,
        targetLanguage: String,
        fallbackHints: List<TextBlock> = emptyList(),
    ): List<TranslatedBlock> = emptyList()

    /** 释放模型 / 连接池等资源 */
    fun close() = Unit
}

class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)
