package com.panmusic.carplayer.data.local

/**
 * 数据库事务执行器 — 测试时可注入零依赖版本
 */
fun interface TransactionRunner {
    suspend fun runInTransaction(block: suspend () -> Unit)
}
