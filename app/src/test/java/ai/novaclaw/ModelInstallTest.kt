package ai.novaclaw

import ai.novaclaw.model.ModelDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelInstallTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun usesPrivateStorageWhenThePrimaryMoveFails() {
        val tmp = files.newFile("qwen.downloading").apply { writeText("test model bytes") }
        val primary = File(files.root, "unavailable/qwen.litertlm")
        val privateDir = files.newFolder("private-models")
        val installed = ModelDownloader.installFile(tmp, primary, privateDir)
        assertEquals(File(privateDir, primary.name), installed)
        assertEquals("test model bytes", installed.readText())
        assertFalse(tmp.exists())
        assertFalse(File(privateDir, "${primary.name}.installing").exists())
    }
}
