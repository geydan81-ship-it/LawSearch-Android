package com.lawsearch.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在 IO 线程执行同步查询（Room 的同步 DAO）。
 */
internal suspend fun <T> dbCall(block: () -> T): T = withContext(Dispatchers.IO) { block() }

/** 已经处于 IO 线程上下文时的内层版本（不做二次派发） */
internal suspend fun <T> ioCall(block: () -> T): T = withContext(Dispatchers.IO) { block() }
