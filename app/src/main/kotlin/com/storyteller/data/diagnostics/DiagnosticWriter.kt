package com.storyteller.data.diagnostics

import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import com.storyteller.data.local.PARSE_VERSION
import com.storyteller.domain.model.PAGE_VISION_MODEL
import com.storyteller.domain.model.PageImage
import com.storyteller.domain.model.ParsedPage
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "DiagnosticWriter"

/**
 * How many bundles to keep. Each holds a full-resolution page photo, so this is
 * a few tens of megabytes at most — enough history to compare several pages
 * against each other without letting a debug aid grow without bound.
 */
internal const val MAX_BUNDLES = 20

/**
 * Records what the vision call was given and what it returned, so a bubble that
 * crops wrongly can be diagnosed off-device instead of guessed at.
 *
 * Recording is best-effort by contract: an implementation must never throw, and
 * never make reading a page depend on writing a diagnostic.
 */
interface DiagnosticWriter {
    suspend fun record(image: PageImage, rawResponse: String, parsed: ParsedPage)

    suspend fun recordFailure(image: PageImage, rawResponse: String, error: Throwable) = Unit
}

/**
 * Writes one directory per page under [root], each holding:
 *
 *  - `page-display.jpg` — the full-resolution upright copy crops are taken FROM.
 *  - `page-upload.jpg` — the downscaled copy the model actually SAW. Both are
 *    kept because a disagreement between them (orientation, aspect) is one of
 *    the candidate explanations for a wrong crop, and one that can only be
 *    excluded by looking at both.
 *  - `response.json` — the model's payload EXACTLY as it arrived. This is the
 *    file that matters most: PageReaderImpl clamps coordinates into 0..1 before
 *    anything else sees them, which is precisely what hid a model returning
 *    `bottom: 1.36` until the cache was read by hand. Recording only the parsed
 *    result would conceal that same evidence again.
 *  - `parse.json` — what the app made of that payload, so raw and interpreted
 *    can be diffed against each other. Failed reads contain `null` here.
 *  - `error.txt` — the exception raised while extracting or parsing the response,
 *    when the bundle was captured from a failed read.
 *  - `meta.json` — both images' real pixel dimensions and the build that
 *    produced them, since normalized bounds mean nothing without the dimensions
 *    they were normalized against, plus the model, resolution tier and parse
 *    version responsible. Without those three a bundle can only be attributed to
 *    a protocol by inferring from its upload size, and this issue has already
 *    been misled once by that kind of inference.
 *
 * The bundle deliberately holds no derived crops. Deriving them on a machine
 * with the page and the boxes in hand is both cheaper and more flexible than
 * baking one interpretation into the capture.
 */
class DiagnosticWriterImpl(private val root: File) : DiagnosticWriter {

    override suspend fun record(image: PageImage, rawResponse: String, parsed: ParsedPage) {
        recordInternal(image, rawResponse, parsed, null)
    }

    override suspend fun recordFailure(image: PageImage, rawResponse: String, error: Throwable) {
        try {
            withContext(Dispatchers.IO) {
                recordInternal(image, rawResponse, null, error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "could not record a failed diagnostic bundle; the page is unaffected", e)
        }
    }

    private suspend fun recordInternal(
        image: PageImage,
        rawResponse: String,
        parsed: ParsedPage?,
        error: Throwable?,
    ) {
        // Cancellation is caught first and rethrown, as everywhere in this
        // codebase: a cancelled read must stay cancelled rather than being
        // converted into a silently-swallowed diagnostic failure.
        try {
            withContext(Dispatchers.IO) { write(image, rawResponse, parsed, error) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "could not record a diagnostic bundle; the page is unaffected", e)
        }
    }

    private fun write(image: PageImage, rawResponse: String, parsed: ParsedPage?, error: Throwable? = null) {
        root.mkdirs()
        val bundle = File(root, stamp()).apply { mkdirs() }

        File(bundle, "page-display.jpg").writeBytes(image.displayBytes)
        File(bundle, "page-upload.jpg").writeBytes(image.bytes)
        File(bundle, "response.json").writeText(rawResponse)
        File(bundle, "parse.json").writeText(parsed?.let(::parseJson) ?: "null\n")
        if (error != null) {
            File(bundle, "error.txt").writeText(
                "${error::class.qualifiedName}: ${error.message ?: "(no message)"}\n",
            )
        }
        File(bundle, "meta.json").writeText(metaJson(image))

        prune()
    }

    /** Sortable and collision-free within a run; bundles are pulled by name. */
    private fun stamp(): String =
        String.format(Locale.US, "page-%013d", System.currentTimeMillis())

    private fun prune() {
        val bundles = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return
        bundles.dropLast(MAX_BUNDLES).forEach { it.deleteRecursively() }
    }

    private fun metaJson(image: PageImage): String {
        val upload = dimensions(image.bytes)
        val display = dimensions(image.displayBytes)
        return """
            {
              "capturedAt": ${System.currentTimeMillis()},
              "uploadWidth": ${upload.first},
              "uploadHeight": ${upload.second},
              "displayWidth": ${display.first},
              "displayHeight": ${display.second},
              "mimeType": "${image.mimeType}",
              "modelId": "${PAGE_VISION_MODEL.id}",
              "resolutionTier": "${PAGE_VISION_MODEL.tier.name}",
              "parseVersion": $PARSE_VERSION,
              "device": "${Build.MANUFACTURER} ${Build.MODEL}",
              "androidSdk": ${Build.VERSION.SDK_INT}
            }
        """.trimIndent()
    }

    /** Bounds-only decode: no pixels are allocated just to read a size. */
    private fun dimensions(jpeg: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        return opts.outWidth to opts.outHeight
    }

    private fun parseJson(parsed: ParsedPage): String {
        val units = parsed.units.joinToString(",\n") { u ->
            val b = u.bounds
            val bounds = if (b == null) {
                "null"
            } else {
                """{"left": ${b.left}, "top": ${b.top}, "right": ${b.right}, "bottom": ${b.bottom}}"""
            }
            """    {"index": ${u.index}, "speaker": ${quote(u.speaker)}, "text": ${quote(u.text)}, "bounds": $bounds}"""
        }
        return "{\n  \"units\": [\n$units\n  ]\n}"
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
