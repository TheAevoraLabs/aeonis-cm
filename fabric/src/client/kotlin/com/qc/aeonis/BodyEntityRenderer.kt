package com.qc.aeonis

import com.qc.aeonis.entity.BodyEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.state.HumanoidRenderState
import net.minecraft.resources.Identifier

class BodyEntityRenderer(ctx: EntityRendererProvider.Context) :
    HumanoidMobRenderer<BodyEntity, BodyEntityRenderState, HumanoidModel<BodyEntityRenderState>>(
        ctx,
        HumanoidModel(ctx.bakeLayer(ModelLayers.PLAYER)),
        0.6f
    ) {

    private val DEFAULT_BODY_TEXTURE = Identifier.fromNamespaceAndPath("aeonis", "textures/entity/body.png")

    override fun createRenderState(): BodyEntityRenderState = BodyEntityRenderState()

    override fun extractRenderState(entity: BodyEntity, state: BodyEntityRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        val owner = entity.getOwnerUuid()
        state.ownerUuid = owner
        if (owner != null) {
            val cached = com.qc.aeonis.skin.SkinCache.getCached(owner)
            if (cached != null) {
                state.skinTexture = cached
            } else {
                // Request an asynchronous fetch; next frames will pick it up
                com.qc.aeonis.skin.SkinCache.ensureFetchAsync(owner)
            }
        }
    }

    override fun getTextureLocation(state: BodyEntityRenderState): Identifier {
        return state.skinTexture ?: DEFAULT_BODY_TEXTURE
    }
}
