package com.xincode.app

import com.xincode.data.ProviderConfigEntity
import com.xincode.data.SessionEntity

internal data class SessionModelOverride(
    val providerConfigId: Long?,
    val modelId: String?
)

internal data class EffectiveModelSelection(
    val provider: ProviderConfigEntity?,
    val modelId: String
)

/** One deterministic interpretation of session and provider configuration state. */
internal object ModelSelection {

    fun normalize(providerConfigId: Long?, modelId: String?): SessionModelOverride =
        SessionModelOverride(
            providerConfigId = providerConfigId?.takeIf { it > 0L },
            modelId = modelId?.trim()?.ifBlank { null }
        )

    /** Quick model switching must not silently discard an explicit session provider. */
    fun quickSwitch(
        session: SessionEntity,
        active: ProviderConfigEntity?,
        modelId: String
    ): SessionModelOverride = normalize(
        providerConfigId = session.modelProviderConfigId?.takeIf { it > 0L } ?: active?.id,
        modelId = modelId
    )

    /**
     * Resolve the same provider/model pair that the request client is expected to use.
     * A deleted explicit provider falls back to active and drops its stale model override;
     * legacy model-only overrides continue to use the active provider with that model.
     */
    fun resolve(
        session: SessionEntity,
        configs: List<ProviderConfigEntity>,
        active: ProviderConfigEntity?
    ): EffectiveModelSelection {
        val explicitId = session.modelProviderConfigId?.takeIf { it > 0L }
        val explicitProvider = explicitId?.let { id -> configs.firstOrNull { it.id == id } }
        val provider = explicitProvider ?: active
        val model = when {
            explicitProvider != null -> session.currentModelId?.trim().orEmpty()
                .ifBlank { explicitProvider.model }
            explicitId != null -> provider?.model.orEmpty()
            else -> session.currentModelId?.trim().orEmpty()
                .ifBlank { provider?.model.orEmpty() }
        }
        return EffectiveModelSelection(provider = provider, modelId = model)
    }
}
