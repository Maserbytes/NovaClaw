package ai.novaclaw

import ai.novaclaw.model.BackendSelector
import ai.novaclaw.model.CloudConfig
import ai.novaclaw.model.ModelCatalog
import ai.novaclaw.safety.SafetyShield
import org.junit.Assert.*
import org.junit.Test

/** Safety guardrails, backend classification and model catalog tests. */
class SafetyAndConfigTest {

    // ---------- SafetyShield — 24 tests ----------
    private fun shield() = SafetyShield()

    @Test fun safe_tap() = assertTrue(shield().evaluate("tap_node", mapOf("node_id" to "n1"), null).allowed)
    @Test fun safe_finish() = assertTrue(shield().evaluate("finish", mapOf("summary" to "Done"), null).allowed)
    @Test fun safe_chat() = assertTrue(shield().evaluate("web_search", mapOf("query" to "hello"), null).allowed)
    @Test fun safe_typing() = assertTrue(shield().evaluate("t", mapOf("text" to "hello"), null).allowed)
    @Test fun safe_openCamera() = assertTrue(shield().evaluate("open_app", mapOf("app_name" to "Camera"), null).allowed)
    @Test fun safe_openWhatsapp() = assertTrue(shield().evaluate("open_app", mapOf("app_name" to "WhatsApp"), null).allowed)
    @Test fun safe_scroll() = assertTrue(shield().evaluate("scroll", mapOf("direction" to "down"), null).allowed)
    @Test fun safe_emptyArgs() = assertTrue(shield().evaluate("tap_node", emptyMap(), null).allowed)
    @Test fun protected_bank() {
        val v = shield().evaluate("open_app", mapOf("app_name" to "Bank Wallet"), null)
        assertTrue(v.requiresConfirmation)
        assertFalse(v.allowed)
        assertNotNull(v.approvalKey)
    }
    @Test fun protected_wallet() {
        val v = shield().evaluate("open_app", mapOf("app_name" to "Google Wallet"), null)
        assertTrue(v.requiresConfirmation)
    }
    @Test fun protected_settings() {
        val v = shield().evaluate("open_app", mapOf("app_name" to "Settings"), null)
        assertTrue(v.requiresConfirmation)
    }
    @Test fun protected_paypal() {
        val v = shield().evaluate("open_app", mapOf("app_name" to "com.paypal"), null)
        assertTrue(v.requiresConfirmation)
    }
    @Test fun protected_explicitPackage() {
        val v = shield().evaluate("open_app", mapOf("app_name" to "com.android.vending"), null)
        assertTrue(v.requiresConfirmation)
    }
    @Test fun destructive_deleteAccount() {
        val v = shield().evaluate("tap_node", mapOf("node_id" to "n1", "label" to "Delete account"), null)
        assertTrue(v.requiresConfirmation)
    }
    @Test fun destructive_wipePhone() {
        val v = shield().evaluate("t", mapOf("text" to "wipe phone now"), null)
        assertTrue(v.requiresConfirmation)
    }
    @Test fun destructive_pay() {
        val v = shield().evaluate("tap_node", mapOf("label" to "Pay $500"), null)
        assertTrue(v.requiresConfirmation)
    }
    @Test fun destructive_transferMoney() {
        val v = shield().evaluate("send_message", mapOf("text" to "transfer money to Bob"), null)
        assertTrue(v.requiresConfirmation)
    }
    @Test fun destructive_disableSafety() {
        val v = shield().evaluate("t", mapOf("text" to "disable safety"), null)
        assertTrue(v.requiresConfirmation)
    }
    @Test fun approval_approveThenCheck() {
        val s = shield()
        val v = s.evaluate("open_app", mapOf("app_name" to "Bank"), null)
        val key = v.approvalKey!!
        assertFalse(s.isApproved(key))
        s.approve(key)
        assertTrue(s.isApproved(key))
    }
    @Test fun approval_denyThenCheck() {
        val s = shield()
        val v = s.evaluate("open_app", mapOf("app_name" to "Bank"), null)
        val key = v.approvalKey!!
        s.deny(key)
        assertFalse(s.isApproved(key))
    }
    @Test fun approval_reset() {
        val s = shield()
        val v = s.evaluate("open_app", mapOf("app_name" to "Bank"), null)
        s.approve(v.approvalKey!!)
        s.reset()
        assertFalse(s.isApproved(v.approvalKey!!))
    }
    @Test fun approval_uniqueKeys() {
        val s = shield()
        val a = s.evaluate("open_app", mapOf("app_name" to "Bank"), null).approvalKey
        val b = s.evaluate("open_app", mapOf("app_name" to "Wallet"), null).approvalKey
        assertNotEquals(a, b)
    }
    @Test fun safe_normalDeleteMessage() {
        // "delete" alone is not destructive without a protected object.
        assertTrue(shield().evaluate("t", mapOf("text" to "delete this word"), null).allowed)
    }
    @Test fun safe_normalBuyBook() {
        // "buy" is in the financial pattern — confirm it is gated.
        val v = shield().evaluate("t", mapOf("text" to "buy me a book"), null)
        assertTrue(v.requiresConfirmation)
    }

    // ---------- BackendSelector classification — 14 tests ----------
    @Test fun gpu_nullError() = assertFalse(BackendSelector.isGpuFailure(null))
    @Test fun gpu_emptyMessage() = assertFalse(BackendSelector.isGpuFailure(RuntimeException("")))
    @Test fun gpu_genericError() = assertFalse(BackendSelector.isGpuFailure(RuntimeException("something else")))
    @Test fun gpu_opencl() = assertTrue(BackendSelector.isGpuFailure(RuntimeException("Can not find OpenCL library")))
    @Test fun gpu_gpuWord() = assertTrue(BackendSelector.isGpuFailure(RuntimeException("GPU init failed")))
    @Test fun gpu_dlopen() = assertTrue(BackendSelector.isGpuFailure(RuntimeException("dlopen failed")))
    @Test fun gpu_badAlloc() = assertTrue(BackendSelector.isGpuFailure(RuntimeException("std::bad_alloc")))
    @Test fun gpu_outOfMemory() = assertTrue(BackendSelector.isGpuFailure(RuntimeException("out of memory")))
    @Test fun gpu_allocation() = assertTrue(BackendSelector.isGpuFailure(RuntimeException("allocation failed")))
    @Test fun gpu_delegate() = assertTrue(BackendSelector.isGpuFailure(RuntimeException("delegate error")))
    @Test fun gpu_compiledModel() = assertTrue(BackendSelector.isGpuFailure(RuntimeException("compiled model failed")))
    @Test fun gpu_sigsegv() = assertTrue(BackendSelector.isGpuFailure(RuntimeException("Fatal signal 11 SIGSEGV")))
    @Test fun gpu_oomError() = assertTrue(BackendSelector.isGpuFailure(OutOfMemoryError()))
    @Test fun gpu_unsatisfiedLink() = assertTrue(BackendSelector.isGpuFailure(UnsatisfiedLinkError("libOpenCL.so")))

    // ---------- ModelCatalog — 16 tests ----------
    @Test fun catalog_gemmaE2bName() = assertTrue(ModelCatalog.GEMMA_E2B.name.contains("Gemma"))
    @Test fun catalog_gemmaE2bFile() = assertEquals("gemma-4-E2B-it.litertlm", ModelCatalog.GEMMA_E2B.fileName)
    @Test fun catalog_gemmaE2bUrl() = assertTrue(ModelCatalog.GEMMA_E2B.url.endsWith(".litertlm"))
    @Test fun catalog_gemmaE2bSize() = assertTrue(ModelCatalog.GEMMA_E2B.sizeBytes > 2_000_000_000L)
    @Test fun catalog_gemmaE2bVision() = assertTrue(ModelCatalog.GEMMA_E2B.vision)
    @Test fun catalog_gemmaE4bVision() = assertTrue(ModelCatalog.GEMMA_E4B.vision)
    @Test fun catalog_gemmaE4bRam() = assertEquals(10, ModelCatalog.GEMMA_E4B.minRamGb)
    @Test fun catalog_qwenExists() = assertEquals("qwen3.5-4b", ModelCatalog.QWEN_35_4B.id)
    @Test fun catalog_qwenFile() = assertEquals("Qwen3.5-4B_mixed_int4.litertlm", ModelCatalog.QWEN_35_4B.fileName)
    @Test fun catalog_qwenNoVision() = assertFalse(ModelCatalog.QWEN_35_4B.vision)
    @Test fun catalog_qwenUrl() = assertTrue(ModelCatalog.QWEN_35_4B.url.contains("Qwen3.5-4B"))
    @Test fun catalog_qwenSize() = assertTrue(ModelCatalog.QWEN_35_4B.sizeBytes > 2_000_000_000L)
    @Test fun catalog_cloudVision() = assertTrue(ModelCatalog.GEMINI.vision)
    @Test fun catalog_localCount() = assertEquals(3, ModelCatalog.LOCAL.size)
    @Test fun catalog_byIdLocal() = assertEquals("gemma4-e2b", ModelCatalog.byId("gemma4-e2b")?.id)
    @Test fun catalog_byIdCloud() = assertEquals("gemini-flash", ModelCatalog.byId("gemini-flash")?.id)

    // ---------- CloudConfig constants — 6 tests ----------
    @Test fun cloud_preferredIsFlash() = assertTrue(CloudConfig.PREFERRED_MODEL.startsWith("gemini"))
    @Test fun cloud_fallbackIsFlash() = assertTrue(CloudConfig.FALLBACK_MODEL.startsWith("gemini"))
    @Test fun cloud_modelsDiffer() = assertNotEquals(CloudConfig.PREFERRED_MODEL, CloudConfig.FALLBACK_MODEL)
    @Test fun cloud_preferredHasVersion() = assertTrue(CloudConfig.PREFERRED_MODEL.contains("flash"))
    @Test fun cloud_fallbackHasVersion() = assertTrue(CloudConfig.FALLBACK_MODEL.contains("flash"))
    @Test fun cloud_noWhitespace() {
        assertEquals(CloudConfig.PREFERRED_MODEL.trim(), CloudConfig.PREFERRED_MODEL)
        assertEquals(CloudConfig.FALLBACK_MODEL.trim(), CloudConfig.FALLBACK_MODEL)
    }
}
