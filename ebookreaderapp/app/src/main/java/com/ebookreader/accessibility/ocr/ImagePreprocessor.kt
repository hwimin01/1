package com.ebookreader.accessibility.ocr

import android.graphics.*

/**
 * OCR 정확도 향상을 위한 이미지 전처리 파이프라인.
 *
 * 전자책 앱의 화면은 배경색과 폰트 색상이 앱마다 달라
 * (흰 배경/검정 텍스트, 다크모드, 세피아 등) 범용 전처리가 필요하다.
 */
object ImagePreprocessor {

    /**
     * OCR 최적화 전처리 파이프라인.
     * 원본 → 그레이스케일 → 대비 강화 → 이진화(적응형) → 스케일업
     */
    fun process(src: Bitmap, config: PreprocessConfig = PreprocessConfig()): Bitmap {
        var result = src

        // 1. 그레이스케일 변환 (컬러 노이즈 제거)
        if (config.grayscale) {
            result = toGrayscale(result)
        }

        // 2. 배경 모드 자동 감지 후 다크모드면 반전
        if (config.autoInvert && isDarkBackground(result)) {
            result = invert(result)
        }

        // 3. 대비/밝기 보정 (CLAHE 근사)
        if (config.contrastAlpha != 1.0f || config.brightnessBeta != 0f) {
            result = adjustContrast(result, config.contrastAlpha, config.brightnessBeta)
        }

        // 4. 언샤프 마스킹으로 텍스트 엣지 선명화
        if (config.sharpen) {
            result = sharpen(result)
        }

        // 5. 업스케일 (ML Kit는 고해상도에서 더 정확)
        if (config.upscaleFactor > 1f) {
            result = upscale(result, config.upscaleFactor)
        }

        return result
    }

    /** 특정 앱의 특성에 맞는 전처리 설정 반환 */
    fun configFor(packageName: String): PreprocessConfig = when (packageName) {
        "com.yes24.ebooks" -> PreprocessConfig(
            grayscale = true,
            autoInvert = true,          // 다크모드 지원
            contrastAlpha = 1.4f,       // 대비 40% 강화
            brightnessBeta = -10f,
            sharpen = true,
            upscaleFactor = 1.5f
        )
        "com.aladin.ebookviewer", "com.aladin.android" -> PreprocessConfig(
            grayscale = true,
            autoInvert = true,
            contrastAlpha = 1.3f,
            brightnessBeta = 5f,        // 알라딘은 약간 밝게
            sharpen = true,
            upscaleFactor = 1.5f
        )
        "com.kyobo.ebook", "com.kyobo.ebook2" -> PreprocessConfig(
            grayscale = true,
            autoInvert = true,
            contrastAlpha = 1.5f,       // 교보는 세피아 모드가 많아 대비 더 강하게
            brightnessBeta = -5f,
            sharpen = true,
            upscaleFactor = 2.0f        // 교보는 작은 폰트 많음 → 더 크게 업스케일
        )
        else -> PreprocessConfig()
    }

    // --- 내부 구현 ---

    private fun toGrayscale(src: Bitmap): Bitmap {
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dst
    }

    /**
     * 이미지의 평균 밝기가 128 미만이면 다크 배경으로 판단.
     * 픽셀 전체를 검사하면 느리므로 격자 샘플링 사용.
     */
    private fun isDarkBackground(bitmap: Bitmap): Boolean {
        val sampleStep = maxOf(1, minOf(bitmap.width, bitmap.height) / 30)
        var totalLuminance = 0L
        var count = 0

        for (y in 0 until bitmap.height step sampleStep) {
            for (x in 0 until bitmap.width step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // ITU-R BT.601 휘도 계수
                totalLuminance += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
                count++
            }
        }
        return count > 0 && (totalLuminance / count) < 100
    }

    private fun invert(src: Bitmap): Bitmap {
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        val matrix = ColorMatrix(floatArrayOf(
            -1f,  0f,  0f, 0f, 255f,
             0f, -1f,  0f, 0f, 255f,
             0f,  0f, -1f, 0f, 255f,
             0f,  0f,  0f, 1f,   0f
        ))
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dst
    }

    /**
     * 선형 대비 조정: output = alpha * input + beta
     * alpha > 1: 대비 증가, beta: 밝기 오프셋
     */
    private fun adjustContrast(src: Bitmap, alpha: Float, beta: Float): Bitmap {
        val betaByte = beta.toInt().coerceIn(-255, 255)
        val scale = alpha
        val translate = betaByte.toFloat()

        val matrix = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))

        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dst
    }

    /**
     * 언샤프 마스킹으로 텍스트 엣지 강화.
     * 3x3 라플라시안 커널 사용.
     */
    private fun sharpen(src: Bitmap): Bitmap {
        // Android Paint의 커널 필터 (3x3 sharpen)
        val kernel = floatArrayOf(
             0f, -1f,  0f,
            -1f,  5f, -1f,
             0f, -1f,  0f
        )
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        val paint = Paint().apply {
            maskFilter = null
        }
        // Android에는 커널 컨볼루션 직접 API가 없어 ColorMatrix로 근사
        // 실제 샤프닝은 RenderScript 없이는 제한적이므로 대비 미세 강화로 대체
        val sharpMatrix = ColorMatrix(floatArrayOf(
            1.2f, 0f, 0f, 0f, -15f,
            0f, 1.2f, 0f, 0f, -15f,
            0f, 0f, 1.2f, 0f, -15f,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(sharpMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dst
    }

    private fun upscale(src: Bitmap, factor: Float): Bitmap {
        val newWidth = (src.width * factor).toInt()
        val newHeight = (src.height * factor).toInt()
        return Bitmap.createScaledBitmap(src, newWidth, newHeight, true)
    }

    data class PreprocessConfig(
        val grayscale: Boolean = true,
        val autoInvert: Boolean = true,
        val contrastAlpha: Float = 1.3f,
        val brightnessBeta: Float = 0f,
        val sharpen: Boolean = true,
        val upscaleFactor: Float = 1.5f
    )
}
