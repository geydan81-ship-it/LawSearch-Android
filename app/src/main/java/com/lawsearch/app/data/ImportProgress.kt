package com.lawsearch.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 首次导入进度（全局单例，首页读取它显示「正在导入 n/766…」）。
 *
 * 为什么需要：766 部法规首次导入要一两分钟，用户看到空白首页会以为卡死。
 */
object ImportProgress {

    data class State(
        val running: Boolean = false,
        /** 0..total */
        val current: Int = 0,
        val total: Int = 0,
        /** 当前正在处理的法规标题 */
        val currentTitle: String = "",
        /** 建索引阶段（导入完成后还要建 FTS） */
        val indexing: Boolean = false,
        val indexed: Int = 0,
        val indexTotal: Int = 0,
        val done: Boolean = false,
    ) {
        val percent: Int
            get() = when {
                indexing && indexTotal > 0 -> 45 + (indexed * 55 / indexTotal)
                total > 0 -> current * 45 / total
                else -> 0
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(total: Int) {
        _state.value = State(running = true, total = total)
    }

    fun step(current: Int, title: String) {
        _state.value = _state.value.copy(current = current, currentTitle = title)
    }

    fun startIndexing(total: Int) {
        _state.value = _state.value.copy(indexing = true, indexTotal = total, indexed = 0)
    }

    fun indexStep(done: Int) {
        _state.value = _state.value.copy(indexed = done)
    }

    fun finish() {
        _state.value = _state.value.copy(running = false, indexing = false, done = true)
    }

    fun reset() {
        _state.value = State()
    }
}
