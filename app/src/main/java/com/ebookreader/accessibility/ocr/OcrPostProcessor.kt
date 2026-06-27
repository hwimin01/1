package com.ebookreader.accessibility.ocr

/**
 * ML Kit OCR 결과의 후처리.
 *
 * 전자책 화면 OCR에서 자주 발생하는 오류 패턴을 교정한다:
 * - 줄바꿈으로 잘린 단어 재결합
 * - 전자책 UI 잔재물(페이지 번호, 퍼센트 등) 필터링
 * - 중복 공백/특수문자 정리
 */
object OcrPostProcessor {

    fun process(rawText: String, packageName: String = ""): String {
        return rawText
            .let { removeUiArtifacts(it, packageName) }
            .let { joinHyphenatedLines(it) }
            .let { normalizeWhitespace(it) }
            .let { fixCommonOcrErrors(it) }
            .trim()
    }

    /**
     * 전자책 앱 고유의 UI 텍스트 제거.
     * (진행률 표시, 페이지 번호, 챕터 헤더 등)
     */
    private fun removeUiArtifacts(text: String, packageName: String): String {
        val lines = text.lines().toMutableList()

        val artifactPatterns = buildList {
            // 공통: 페이지 번호 패턴 (예: "123 / 456", "p.12")
            add(Regex("""^\s*\d+\s*/\s*\d+\s*$"""))
            add(Regex("""^\s*p\.\s*\d+\s*$""", RegexOption.IGNORE_CASE))
            add(Regex("""^\s*\d+\s*%\s*$"""))  // 진행률
            add(Regex("""^\s*\d+\s*$"""))       // 숫자만 있는 줄 (페이지 번호)

            // 앱별 추가 패턴
            when (packageName) {
                "com.yes24.ebooks" -> {
                    add(Regex("""YES24""", RegexOption.IGNORE_CASE))
                }
                "com.aladin.ebookviewer", "com.aladin.android" -> {
                    add(Regex("""aladin""", RegexOption.IGNORE_CASE))
                    add(Regex("""www\.aladin""", RegexOption.IGNORE_CASE))
                }
                "com.kyobo.ebook", "com.kyobo.ebook2" -> {
                    add(Regex("""kyobo|교보문고""", RegexOption.IGNORE_CASE))
                    add(Regex("""SAM"""))
                }
            }
        }

        return lines
            .filter { line -> artifactPatterns.none { it.containsMatchIn(line.trim()) } }
            .joinToString("\n")
    }

    /**
     * 하이픈으로 분리된 단어 재결합.
     * 예: "안녕하-\n세요" → "안녕하세요"
     */
    private fun joinHyphenatedLines(text: String): String {
        return text.replace(Regex("""[-‐]\n(\S)"""), "$1")
    }

    /**
     * 공백/줄바꿈 정규화.
     * 여러 줄바꿈 → 문단 구분(2줄), 여러 공백 → 1공백
     */
    private fun normalizeWhitespace(text: String): String {
        return text
            .replace(Regex(""" {2,}"""), " ")      // 다중 공백 → 단일 공백
            .replace(Regex("""\n{3,}"""), "\n\n")  // 3줄 이상 개행 → 2줄
            .replace(Regex("""[ \t]+\n"""), "\n")  // 줄 끝 공백 제거
    }

    /**
     * 한국어 OCR에서 자주 발생하는 오인식 교정.
     * ML Kit는 한국어 자모 분리 오류가 종종 발생한다.
     */
    private fun fixCommonOcrErrors(text: String): String {
        return text
            // 따옴표 정규화
            .replace("“", "\"").replace("”", "\"")
            .replace("‘", "'").replace("’", "'")
            // 전각 문자 → 반각
            .replace("，", ",").replace("。", ".")
            .replace("！", "!").replace("？", "?")
            // 중간점
            .replace("·", " ")
            // 줄임표 정규화
            .replace("…", "...")
    }
}
