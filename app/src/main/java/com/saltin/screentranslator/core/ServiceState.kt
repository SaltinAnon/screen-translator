package com.saltin.screentranslator.core

import com.saltin.screentranslator.translate.PipelineStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 服务与 UI 之间的单向状态通道。UI 只读，服务只写。
 */
object ServiceState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _status = MutableStateFlow("未启动")
    val status: StateFlow<String> = _status

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _stats = MutableStateFlow<PipelineStats?>(null)
    val stats: StateFlow<PipelineStats?> = _stats

    /** 屏幕上那条提示条的文案（同时也会在设置页顶部展示） */
    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint

    /** 悬浮球最近一次的译文条数 */
    private val _lastBlocks = MutableStateFlow(0)
    val lastBlocks: StateFlow<Int> = _lastBlocks

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun setStatus(text: String) {
        _status.value = text
    }

    fun setError(text: String?) {
        _lastError.value = text
    }

    fun setStats(value: PipelineStats?) {
        _stats.value = value
    }

    fun setHint(text: String?) {
        _hint.value = text
    }

    fun setLastBlocks(value: Int) {
        _lastBlocks.value = value
    }

    fun resetForStop(reason: String) {
        _running.value = false
        _status.value = reason
        _stats.value = null
        _lastBlocks.value = 0
    }
}
