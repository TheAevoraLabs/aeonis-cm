package com.qc.aeonis.neoforge.client

import net.minecraft.client.renderer.entity.state.HumanoidRenderState
import net.minecraft.resources.Identifier
import java.util.UUID

class BodyEntityRenderState : HumanoidRenderState() {
    var ownerUuid: UUID? = null
    var skinTexture: Identifier? = null
}
