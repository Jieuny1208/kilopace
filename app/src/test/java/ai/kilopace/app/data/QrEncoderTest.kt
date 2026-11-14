package ai.kilopace.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrEncoderTest {

    private fun csv(rows: Int) = buildString {
        append("title\n"); append("h1,h2\n")
        repeat(rows) { append("row$it,").append("데이터".repeat(10)).append('\n') }
    }

    @Test fun `small csv is kept whole`() {
        val (out, truncated) = QrEncoder.fitForQr(csv(5))
        assertFalse(truncated)
        assertEquals(csv(5), out)
    }

    @Test fun `large csv keeps header and the most recent rows under the limit`() {
        val (out, truncated) = QrEncoder.fitForQr(csv(400))
        assertTrue(truncated)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= QrEncoder.MAX_BYTES)
        val lines = out.lines()
        assertEquals("title", lines[0]); assertEquals("h1,h2", lines[1])
        assertTrue(lines.last().startsWith("row399,"))
    }
}
