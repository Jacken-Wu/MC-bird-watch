package com.birdwatch.client.mixin;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 GameRenderer 内部字段:
 * resourcePool(PostChain.process 需要 GraphicsResourceAllocator);
 * guiRenderer(帧末 DoF 拷贝后重绘 HUD)。
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Accessor("resourcePool")
	CrossFrameResourcePool birdwatch$resourcePool();

	@Accessor("guiRenderer")
	GuiRenderer birdwatch$guiRenderer();
}
