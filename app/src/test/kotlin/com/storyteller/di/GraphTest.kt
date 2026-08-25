package com.storyteller.di

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hilt graph validity is enforced at compile time by the annotation processor, so
 * this test guards the things the processor cannot see: that no module has gone
 * missing, and that the layering rule still holds.
 */
class GraphTest {

    private val srcMain = File("src/main/kotlin/com/storyteller")

    @Test fun `all four modules exist`() {
        listOf("NetworkModule", "DatabaseModule", "RepositoryModule", "PipelineModule")
            .forEach { assertTrue("$it missing", File(srcMain, "di/$it.kt").exists()) }
    }

    @Test fun `ui never imports data`() {
        // ui does not exist until Tasks 11-12 create it, so an absent ui/ directory
        // must not fail this test. But a *typo'd* path (e.g. checking "iu" instead
        // of "ui") would also present as "absent" and silently pass forever, even
        // after ui/ is created for real — so first assert the path scheme itself is
        // right, via sibling directories that are known to exist right now.
        assertTrue(
            "expected $srcMain/domain and $srcMain/data to exist; is srcMain right?",
            File(srcMain, "domain").isDirectory && File(srcMain, "data").isDirectory,
        )

        val uiDir = File(srcMain, "ui")
        if (!uiDir.exists()) return

        val offenders = uiDir.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { f -> f.readLines().any { it.startsWith("import com.storyteller.data") } }
            .map { it.name }
            .toList()
        assertTrue("ui imports data in: $offenders", offenders.isEmpty())
    }

    @Test fun `domain never imports android`() {
        val offenders = File(srcMain, "domain").walkTopDown()
            .filter { it.extension == "kt" }
            .filter { f ->
                f.readLines().any {
                    it.startsWith("import android.") || it.startsWith("import androidx.")
                }
            }
            .map { it.name }
            .toList()
        assertTrue("domain imports Android in: $offenders", offenders.isEmpty())
    }
}
