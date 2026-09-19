package com.qc.aeonis.neoforge.client

import com.qc.aeonis.entity.BodyEntity
import com.qc.aeonis.neoforge.skin.SkinCache
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
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
            val cached = SkinCache.getCached(owner)
            if (cached != null) {
                state.skinTexture = cached
            } else {
                SkinCache.ensureFetchAsync(owner)
            }
        }
    }

    override fun getTextureLocation(state: BodyEntityRenderState): Identifier {
        return state.skinTexture ?: DEFAULT_BODY_TEXTURE
    }
}
