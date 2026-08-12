package com.xincode.app

import com.xincode.data.ProviderConfigEntity
import com.xincode.data.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSelectionTest {

    private val active = ProviderConfigEntity(
        id = 10L,
        name = "主供应商",
        supplierId = "main",
        baseUrl = "https://main.invalid",
        apiKeyEnc = "encrypted",
        model = "main-default",
        enabledModelIds = listOf("main-default", "main-fast"),
        isActive = true
    )
    private val alternate = ProviderConfigEntity(
        id = 20L,
        name = "备用供应商",
        supplierId = "backup",
        baseUrl = "https://backup.invalid",
        apiKeyEnc = "encrypted",
        model = "backup-default",
        enabledModelIds = listOf("backup-default", "backup-reasoning")
    )

    @Test
    fun sessionOverrideWinsOverActiveConfigAndUsesItsDefaultModel() {
        val session = SessionEntity(modelProviderConfigId = alternate.id)

        val selected = ModelSelection.resolve(session, listOf(active, alternate), active)

        assertEquals(alternate.id, selected.provider?.id)
        assertEquals("backup-default", selected.modelId)
    }

    @Test
    fun modelOverrideWithoutProviderStillUsesTheActiveProvider() {
        val session = SessionEntity(currentModelId = "main-fast")

        val selected = ModelSelection.resolve(session, listOf(active, alternate), active)

        assertEquals(active.id, selected.provider?.id)
        assertEquals("main-fast", selected.modelId)
    }

    @Test
    fun quickSwitchKeepsExplicitProviderAndLocksActiveProviderWhenFollowingIt() {
        val explicit = SessionEntity(modelProviderConfigId = alternate.id)
        val followingActive = SessionEntity()

        val explicitResult = ModelSelection.quickSwitch(explicit, active, "backup-reasoning")
        val activeResult = ModelSelection.quickSwitch(followingActive, active, "main-fast")

        assertEquals(alternate.id, explicitResult.providerConfigId)
        assertEquals("backup-reasoning", explicitResult.modelId)
        assertEquals(active.id, activeResult.providerConfigId)
        assertEquals("main-fast", activeResult.modelId)
    }

    @Test
    fun clearingFollowActiveSelectionStoresNoOverride() {
        val result = ModelSelection.normalize(null, " ")

        assertNull(result.providerConfigId)
        assertNull(result.modelId)
    }
}
