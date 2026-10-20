package com.lpdf.reader.core.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 全局调度器。集中管理，禁止在业务层直接写死 Dispatchers.X。
 */
object AppDispatchers {
    val io: CoroutineDispatcher = Dispatchers.IO
    val default: CoroutineDispatcher = Dispatchers.Default
    val main: CoroutineDispatcher = Dispatchers.Main

    /** PDFium 单线程串行队列（非线程安全） */
    val pdf: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
}
