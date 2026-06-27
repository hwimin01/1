package com.ebookreader.accessibility.search

object SearchAdapterFactory {

    private val adapters: Map<String, AppSearchAdapter> = buildMap {
        // YES24 — 단일 패키지
        Yes24SearchAdapter().also { put(it.packageName, it) }

        // 알라딘 — 두 가지 패키지 버전
        AladinSearchAdapter().also {
            put(it.packageName, it)
            put("com.aladin.android", it)          // 통합 앱 버전
        }

        // 교보문고 — 세 가지 패키지 버전
        KyoboSearchAdapter().also {
            put(it.packageName, it)
            put("com.kyobo.ebook2", it)             // SAM 버전
            put("com.kyobo.b2b.ebook", it)          // B2B 버전
        }
    }

    fun get(packageName: String): AppSearchAdapter? = adapters[packageName]

    val supportedPackages: Set<String> get() = adapters.keys
}
