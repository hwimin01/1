package com.ebookreader.accessibility.search

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 교보문고 ebook 앱 검색창 어댑터.
 *
 * 교보문고(com.kyobo.ebook)는 하단 탭 네비게이션 구조를 사용하며
 * 검색 탭을 누르면 별도 Fragment로 전환된다.
 * 검색 EditText 힌트: "제목, 저자, 출판사, ISBN"
 * 리소스 ID: com.kyobo.ebook:id/et_search
 *
 * 교보문고 ebook2(sam) 앱도 지원:
 *   패키지: com.kyobo.ebook2 또는 com.kyobo.b2b.ebook
 */
class KyoboSearchAdapter : AppSearchAdapter {

    override val packageName = "com.kyobo.ebook"

    private val candidateIds = listOf(
        "com.kyobo.ebook:id/et_search",
        "com.kyobo.ebook:id/search_src_text",
        "com.kyobo.ebook:id/searchEditText",
        "com.kyobo.ebook:id/input_keyword",
        // SAM(교보 SAM) 버전
        "com.kyobo.ebook2:id/et_search",
        "com.kyobo.ebook2:id/searchEditText"
    )

    private val hintTexts = listOf(
        "제목, 저자, 출판사",
        "검색어를 입력",
        "search",
        "isbn",
        "제목"
    )

    override fun findSearchNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null

        // 1순위: 리소스 ID
        NodeFinder.byViewIds(root, *candidateIds.toTypedArray())
            ?.let { return it }

        // 2순위: 힌트 텍스트
        NodeFinder.byHintText(root, *hintTexts.toTypedArray())
            ?.let { return it }

        // 3순위: 교보문고는 검색 화면 전환 후 EditText가 단일로 존재
        val editTexts = NodeFinder.editTextNodes(root)
        if (editTexts.size == 1) return editTexts.first()

        // 4순위: 화면 중앙 상단부의 EditText (검색 Fragment 구조)
        val screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels
        return editTexts.filter { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top < screenHeight / 3
        }.minByOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        }
    }

    override fun submitSearch(root: AccessibilityNodeInfo?, query: String): Boolean {
        val node = findSearchNode(root) ?: return false

        // 교보문고: 검색창 클릭 → 텍스트 입력 → IME 실행
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(300)

        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                query
            )
        }
        val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!set) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
    }
}
