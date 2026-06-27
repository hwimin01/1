package com.ebookreader.accessibility.search

import android.view.accessibility.AccessibilityNodeInfo

/**
 * YES24 ebook 앱 검색창 어댑터.
 *
 * YES24 앱(com.yes24.ebooks)은 상단 툴바에 검색 아이콘을 탭하면
 * 별도 SearchActivity로 전환되며, 검색 EditText가 열린다.
 * 리소스 ID: com.yes24.ebooks:id/search_src_text (AppCompat SearchView 내부)
 * 또는 com.yes24.ebooks:id/et_search (커스텀 레이아웃)
 */
class Yes24SearchAdapter : AppSearchAdapter {

    override val packageName = "com.yes24.ebooks"

    // 알려진 후보 ID 목록 — 앱 업데이트 시 변경될 수 있으므로 다중 후보 사용
    private val candidateIds = listOf(
        "com.yes24.ebooks:id/search_src_text",  // AppCompat SearchView
        "com.yes24.ebooks:id/et_search",
        "com.yes24.ebooks:id/input_search",
        "com.yes24.ebooks:id/search_edit"
    )

    private val hintTexts = listOf("검색어", "search", "제목", "도서")

    override fun findSearchNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null

        // 1순위: 리소스 ID로 탐색
        NodeFinder.byViewIds(root, *candidateIds.toTypedArray())
            ?.let { return it }

        // 2순위: 힌트 텍스트로 탐색
        NodeFinder.byHintText(root, *hintTexts.toTypedArray())
            ?.let { return it }

        // 3순위: 화면에 EditText가 하나뿐이면 그것이 검색창
        val editTexts = NodeFinder.editTextNodes(root)
        if (editTexts.size == 1) return editTexts.first()

        // 4순위: 여러 EditText 중 상단(y좌표 가장 작은) 것 선택
        return editTexts.minByOrNull { it.getBoundsInScreen().top }
    }

    override fun submitSearch(root: AccessibilityNodeInfo?, query: String): Boolean {
        val node = findSearchNode(root) ?: return false
        NodeFinder.requestFocus(node)
        val set = NodeFinder.setText(node, query)
        if (!set) return false
        return NodeFinder.performImeAction(node)
    }

    // YES24는 검색 화면 진입 시 EditText가 자동으로 포커스를 받음
    override fun isSearchFocused(root: AccessibilityNodeInfo?): Boolean {
        return findSearchNode(root)?.isFocused == true
    }

    private fun AccessibilityNodeInfo.getBoundsInScreen(): android.graphics.Rect {
        val rect = android.graphics.Rect()
        getBoundsInScreen(rect)
        return rect
    }
}
