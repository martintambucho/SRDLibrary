package com.example.pocfacerecognition.common

import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.network.okHttpClient
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

val apolloClient = ApolloClient.Builder()
    .serverUrl("https://srd-dev-api.bla-development.com/")
    .okHttpClient(
        OkHttpClient.Builder()
            .addInterceptor(AuthorizationInterceptor())
            .build()
    )
    .build()

private class AuthorizationInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .apply {
                addHeader(
                    "X-API-KEY", SRDPreferences.read(SRDPreferences.API_KEY, "")
                )
            }
            .build()
        return chain.proceed(request)
    }
}