package com.storyteller.data.diagnostics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.storyteller.domain.model.BoundingBox
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedPage
import com.storyteller.domain.model.ParsedUnit
import com.storyteller.domain.model.toSpeechUnits
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticWriterImplTest {

    @get:Rule val temp = TemporaryFolder()

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            .toByteArray()
    }

    /** Distinct dimensions so a bundle that swapped the two copies would be visible. */
    private fun pageImage() = PageImage(
        bytes = jpeg(400, 300),
        mimeType = "image/jpeg",
        displayBytes = jpeg(800, 600),
    )

    private fun parsed() = ParsedPage(
        listOf(ParsedUnit("Wolf", "RIGHT?!", BoundingBox(0.1f, 0.1f, 0.4f, 0.3f))).toSpeechUnits(),
    )

    private fun writer(dir: File = temp.newFolder("diagnostics")) = DiagnosticWriterImpl(dir)

    private fun bundles(dir: File): List<File> =
        dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()

    @Test fun `writes the page, the raw response and the parse into one bundle`() = runTest {
        val dir = temp.newFolder("diagnostics")

        writer(dir).record(pageImage(), rawResponse = """{"units":[]}""", parsed = parsed())

        val bundle = bundles(dir).single()
        assertTrue("page-display.jpg", File(bundle, "page-display.jpg").length() > 0)
        assertTrue("page-upload.jpg", File(bundle, "page-upload.jpg").length() > 0)
        assertTrue("response.json", File(bundle, "response.json").length() > 0)
        assertTrue("parse.json", File(bundle, "parse.json").length() > 0)
        assertTrue("meta.json", File(bundle, "meta.json").length() > 0)
    }

    /**
     * The point of the whole bundle. PageReaderImpl clamps coordinates into 0..1
     * before anything else sees them, which is exactly what hid a model returning
     * `bottom: 1.36` until the cache was read by hand. A diagnostic that recorded
     * only the clamped parse would conceal the same evidence again.
     */
    @Test fun `response json preserves coordinates the client would clamp away`() = runTest {
        val dir = temp.newFolder("diagnostics")
        val raw = """{"units":[{"speaker":"Wolf","text":"HI","bounds":{"left":0.5,"top":1.08,"right":0.88,"bottom":1.36}}]}"""

        writer(dir).record(pageImage(), rawResponse = raw, parsed = parsed())

        val written = File(bundles(dir).single(), "response.json").readText()
        assertTrue("raw out-of-range values must survive verbatim: $written", written.contains("1.36"))
        assertEquals(raw, written)
    }

    @Test fun `failed response keeps the page and records the parsing error`() = runTest {
        val dir = temp.newFolder("diagnostics")
        val raw = """{"stop_reason":"refusal","content":[]}"""

        writer(dir).recordFailure(pageImage(), raw, IllegalStateException("no text block"))

        val bundle = bundles(dir).single()
        assertEquals(raw, File(bundle, "response.json").readText())
        assertEquals("null\n", File(bundle, "parse.json").readText())
        assertTrue(File(bundle, "page-display.jpg").length() > 0)
        assertTrue(File(bundle, "page-upload.jpg").length() > 0)
        assertTrue(File(bundle, "error.txt").readText().contains("no text block"))
    }

    @Test fun `meta records both images' real dimensions`() = runTest {
        val dir = temp.newFolder("diagnostics")

        writer(dir).record(pageImage(), rawResponse = """{"units":[]}""", parsed = parsed())

        val meta = File(bundles(dir).single(), "meta.json").readText()
        assertTrue("upload 400x300 in $meta", meta.contains("\"uploadWidth\": 400"))
        assertTrue("display 800x600 in $meta", meta.contains("\"displayWidth\": 800"))
    }

    @Test fun `keeps only the most recent bundles`() = runTest {
        val dir = temp.newFolder("diagnostics")
        val w = writer(dir)

        repeat(MAX_BUNDLES + 3) { w.record(pageImage(), """{"units":[]}""", parsed()) }

        assertEquals(MAX_BUNDLES, bundles(dir).size)
    }

    /**
     * A diagnostic must never cost the reader a page. An unwritable directory is
     * the cheapest way to prove the failure is swallowed rather than propagated.
     */
    @Test fun `a write failure never propagates`() = runTest {
        val notADirectory = temp.newFile("occupied")

        DiagnosticWriterImpl(notADirectory).record(pageImage(), """{"units":[]}""", parsed())
    }
}
