package com.panmusic.carplayer.di

import javax.inject.Qualifier

/**
 * 自定义 Qualifier 注解 — 区分同类注入
 */

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PanApi

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OAuthApi

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthPrefs
