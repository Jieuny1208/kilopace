package ai.kilopace.app.data

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** CSV 텍스트를 QR로 만들어 정차 중 스마트폰으로 받아갈 수 있게 한다 (서버·공유 인텐트 불필요). */
object QrEncoder {

    /** QR 바이트 모드 상한(버전 40, L) 근처. 초과하면 요약만 담는다. */
    const val MAX_BYTES = 2800

    fun encode(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        // setPixel 40만 회 호출 대신 한 번에 채운다 (메인 스레드 지연 방지)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val row = y * sizePx
            for (x in 0 until sizePx) pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        bmp.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        return bmp
    }

    /** 전체 CSV가 상한을 넘으면 최근 행부터 잘라 담는다. */
    fun fitForQr(csv: String): Pair<String, Boolean> {
        val bytes = csv.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_BYTES) return csv to false
        val lines = csv.lines()
        val head = lines.take(2)
        val tail = lines.drop(2).filter { it.isNotBlank() }
        val kept = mutableListOf<String>()
        var size = head.sumOf { it.toByteArray().size + 1 }
        for (line in tail.asReversed()) {
            val n = line.toByteArray().size + 1
            if (size + n > MAX_BYTES) break
            kept.add(0, line); size += n
        }
        return (head + kept).joinToString("\n") to true
    }
}
