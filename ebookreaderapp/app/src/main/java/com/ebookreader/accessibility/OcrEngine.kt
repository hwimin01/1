package com.ebookreader.accessibility

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrEngine {

    private val koreanRecognizer: TextRecognizer =
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    private val latinRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * 비트맵에서 텍스트 인식.
     * 한국어 인식 결과가 없으면 라틴(영문) 인식으로 폴백.
     */
    suspend fun recognizeText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)

        val koreanResult = runCatching { recognizeWith(image, koreanRecognizer) }.getOrNull()
        if (!koreanResult.isNullOrBlank()) return koreanResult

        return runCatching { recognizeWith(image, latinRecognizer) }.getOrDefault("")
    }

    /**
     * 두 인식기를 병렬로 실행하여 결과를 병합.
     * 혼합 문서(한국어 + 영어 표)에 사용.
     */
    suspend fun recognizeMixed(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)

        val korean = runCatching { recognizeWith(image, koreanRecognizer) }.getOrDefault("")
        val latin = runCatching { recognizeWith(image, latinRecognizer) }.getOrDefault("")

        // 더 긴 결과 선택 (일반적으로 더 많이 인식한 쪽이 우수)
        return if (korean.length >= latin.length) korean else latin
    }

    private suspend fun recognizeWith(image: InputImage, recognizer: TextRecognizer): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(sortAndJoin(result))
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                }
        }

    /**
     * OCR 블록을 읽기 순서대로 정렬하여 하나의 문자열로 결합.
     *
     * 전자책은 단일 컬럼 레이아웃이 대부분이므로
     * 상단→하단 순으로 정렬하면 자연스러운 읽기 순서가 된다.
     * 예외: 2단 레이아웃은 좌→우를 먼저 적용한다.
     */
    private fun sortAndJoin(result: Text): String {
        if (result.textBlocks.isEmpty()) return ""

        // 블록 경계박스의 평균 너비로 컬럼 레이아웃 여부 추정
        val avgBlockWidth = result.textBlocks
            .mapNotNull { it.boundingBox?.width() }
            .average()
            .let { if (it.isNaN()) 0.0 else it }

        val imageWidth = result.textBlocks
            .mapNotNull { it.boundingBox?.right }
            .maxOrNull() ?: 1

        val isTwoColumn = avgBlockWidth < imageWidth * 0.45

        val sorted = if (isTwoColumn) {
            // 2단: 좌측 컬럼(x < 중앙) 먼저, 각 컬럼 내에서는 상→하
            val midX = imageWidth / 2
            val leftBlocks = result.textBlocks
                .filter { (it.boundingBox?.centerX() ?: 0) < midX }
                .sortedBy { it.boundingBox?.top ?: 0 }
            val rightBlocks = result.textBlocks
                .filter { (it.boundingBox?.centerX() ?: 0) >= midX }
                .sortedBy { it.boundingBox?.top ?: 0 }
            leftBlocks + rightBlocks
        } else {
            // 단일 컬럼: 상→하
            result.textBlocks.sortedWith(
                compareBy(
                    { it.boundingBox?.top ?: 0 },
                    { it.boundingBox?.left ?: 0 }
                )
            )
        }

        return sorted.joinToString("\n") { block ->
            block.lines.joinToString(" ") { line ->
                line.elements.joinToString("") { element -> element.text }
            }
        }
    }

    fun close() {
        koreanRecognizer.close()
        latinRecognizer.close()
    }
}
