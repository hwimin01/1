package com.ebookreader.accessibility.search

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 알라딘 ebook 앱 검색창 어댑터.
 *
 * 알라딘(com.aladin.ebookviewer)은 메인 화면 상단에 검색바가 항상 노출된다.
 * 뷰어 내부에서는 별도 검색 버튼을 통해 진입한다.
 * 리소스 ID: com.aladin.ebookviewer:id/et_search_keyword
 * 또는 com.aladin.ebookviewer:id/searchView
 *
 * 알라딘은 패키지명이 두 가지 버전이 존재:
 *   - com.aladin.ebookviewer (ebook 전용)
 *   - com.aladin.android (통합 앱, 내부 검색 포함)
 */
class AladinSearchAdapter : AppSearchAdapter {

    override val packageName = "com.aladin.ebookviewer"

    private val candidateIds = listOf(
        "com.aladin.ebookviewer:id/et_search_keyword",
        "com.aladin.ebookviewer:id/search_src_text",
        "com.aladin.ebookviewer:id/searchEditText",
        "com.aladin.ebookviewer:id/et_keyword",
        // 통합 앱 버전
        "com.aladin.android:id/et_search_keyword",
        "com.aladin.android:id/search_src_text"
    )

    private val hintTexts = listOf("검색어 입력", "검색", "제목, 저자", "도서명")

    override fun findSearchNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null

        // 1순위: 리소스 ID
        NodeFinder.byViewIds(root, *candidateIds.toTypedArray())
            ?.let { return it }

        // 2순위: 힌트 텍스트
        NodeFinder.byHintText(root, *hintTexts.toTypedArray())
            ?.let { return it }

        // 3순위: 화면 상단 1/4 영역에 있는 EditText (알라딘은 상단 고정 검색바)
        val editTexts = NodeFinder.editTextNodes(root)
        val screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels
        val topQuarter = screenHeight / 4

        return editTexts.filter { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top < topQuarter
        }.minByOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        } ?: editTexts.firstOrNull()
    }

    override fun submitSearch(root: AccessibilityNodeInfo?, query: String): Boolean {
        val node = findSearchNode(root) ?: return false

        // 알라딘은 먼저 클릭으로 검색창 활성화 필요
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(200)

        val set = NodeFinder.setText(node, query)
        if (!set) return false
        return NodeFinder.performImeAction(node)
    }
}
