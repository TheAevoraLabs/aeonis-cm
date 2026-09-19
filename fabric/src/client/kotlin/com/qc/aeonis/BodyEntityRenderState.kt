package com.qc.aeonis

import java.util.UUID
import net.minecraft.resources.Identifier
import net.minecraft.client.renderer.entity.state.HumanoidRenderState

class BodyEntityRenderState : HumanoidRenderState() {
    var ownerUuid: UUID? = null
    var skinTexture: Identifier? = null
}
