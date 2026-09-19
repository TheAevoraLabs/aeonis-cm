package com.qc.aeonis.neoforge.client

import com.qc.aeonis.entity.HerobrineEntity
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.renderer.entity.state.HumanoidRenderState
import net.minecraft.resources.Identifier

class HerobrineEntityRenderer(ctx: EntityRendererProvider.Context) :
    HumanoidMobRenderer<HerobrineEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>>(
        ctx,
        HumanoidModel(ctx.bakeLayer(ModelLayers.PLAYER)),
        0.0f
    ) {

    companion object {
        private val HEROBRINE_TEXTURE = Identifier.fromNamespaceAndPath("aeonis", "textures/entity/herobrine.png")
    }

    override fun createRenderState(): HumanoidRenderState = HumanoidRenderState()

    override fun getTextureLocation(state: HumanoidRenderState): Identifier = HEROBRINE_TEXTURE
}
