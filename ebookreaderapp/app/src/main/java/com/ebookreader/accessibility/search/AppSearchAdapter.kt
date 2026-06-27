package com.ebookreader.accessibility.search

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 앱별 검색창 탐색·입력 전략의 공통 인터페이스.
 * 각 전자책 앱은 검색 EditText의 리소스 ID와 위치가 달라
 * 앱마다 별도 구현이 필요하다.
 */
interface AppSearchAdapter {

    /** 이 어댑터가 처리하는 앱의 패키지명 */
    val packageName: String

    /**
     * 현재 화면에서 검색 입력창 노드를 찾아 반환한다.
     * 없으면 null.
     */
    fun findSearchNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo?

    /**
     * 검색 입력창에 텍스트를 입력하고 검색을 실행한다.
     * @return 성공 여부
     */
    fun submitSearch(root: AccessibilityNodeInfo?, query: String): Boolean

    /**
     * 검색창이 현재 포커스를 갖고 있는지 확인한다.
     */
    fun isSearchFocused(root: AccessibilityNodeInfo?): Boolean {
        val node = findSearchNode(root) ?: return false
        return node.isFocused
    }
}
