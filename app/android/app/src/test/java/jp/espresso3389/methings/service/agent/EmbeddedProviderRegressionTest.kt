package jp.espresso3389.methings.service.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class EmbeddedProviderRegressionTest {

    @Test
    fun resolveProviderUrlUsesEmbeddedScheme() {
        val manager = AgentConfigManager(mock())
        assertEquals("embedded://local", manager.resolveProviderUrl("embedded", ""))
        assertEquals("embedded://local", manager.resolveProviderUrl("embedded", "embedded://custom"))
    }

    @Test
    fun detectProviderKindRecognizesEmbeddedVendorAndUrl() {
        val client = LlmClient()
        assertEquals(ProviderKind.EMBEDDED, client.detectProviderKind("embedded://local", "embedded"))
        assertEquals(ProviderKind.EMBEDDED, client.detectProviderKind("embedded://other", "custom"))
    }

    @Test
    fun gemma4CatalogEntryIsTextOnlyToolCallingModel() {
        val spec = EmbeddedModelCatalog.find("gemma4-e2b-it")
        assertNotNull(spec)
        assertTrue(spec!!.supportsToolCalling)
        assertFalse(spec.supportsImageInput)
        assertFalse(spec.supportsAudioInput)
        assertEquals("litert_lm", spec.preferredBackend)
    }
}
