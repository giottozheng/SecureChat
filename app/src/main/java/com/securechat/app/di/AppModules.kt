package com.securechat.app.di

import com.securechat.app.crypto.EncryptionEngine
import com.securechat.app.crypto.MessageEncryptor
import com.securechat.app.network.api.ApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import com.securechat.app.util.ServerConfig

/**
 * Crypto module — provides encryption engine and message encryptor.
 */
@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideEncryptionEngine(): EncryptionEngine {
        return EncryptionEngine()
    }

    // MessageEncryptor is @Singleton with @Inject constructor — Hilt auto-injects it

    @Provides
    @Singleton
    fun provideRsaKeystoreManager(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context
    ): com.securechat.app.crypto.keyexchange.RsaKeystoreManager {
        val manager = com.securechat.app.crypto.keyexchange.RsaKeystoreManager(context)
        // 确保团队公钥缓存已初始化（所有成员密钥存于 AndroidKeyStore，可跨实例读取）
        manager.initAllTeamKeys()
        return manager
    }
}

/**
 * Network module — provides Retrofit, OkHttp, and API service.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        val interceptor = HttpLoggingInterceptor()
        interceptor.level = HttpLoggingInterceptor.Level.BODY
        return interceptor
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(loggingInterceptor: HttpLoggingInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // 动态重写请求主机：统一走 ServerConfig（支持用户在设置里绑定自定义服务端）。
            // Retrofit 的 baseUrl 仅作占位，真实地址在请求时按 ServerConfig 注入。
            .addInterceptor { chain ->
                val original = chain.request()
                val base = ServerConfig.getBaseUrl()
                val rewritten = base.toHttpUrlOrNull()?.newBuilder()
                    ?.encodedPath(original.url.encodedPath)
                    ?.encodedQuery(original.url.encodedQuery)
                    ?.build() ?: original.url
                chain.proceed(original.newBuilder().url(rewritten).build())
            }
            .addInterceptor(loggingInterceptor)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}