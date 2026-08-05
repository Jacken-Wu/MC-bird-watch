package com.birdwatch.client.mixin;

import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 ShaderManager 的后处理链投影参数(PostChain.load 需要)。
 */
@Mixin(ShaderManager.class)
public interface ShaderManagerAccessor {
	@Accessor("postChainProjection")
	Projection birdwatch$postChainProjection();

	@Accessor("postChainProjectionMatrixBuffer")
	ProjectionMatrixBuffer birdwatch$postChainProjectionMatrixBuffer();
}
