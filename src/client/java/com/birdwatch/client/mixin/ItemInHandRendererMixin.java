package com.birdwatch.client.mixin;

import com.birdwatch.client.CameraSession;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 取景器内不渲染第一人称手臂/手持物品(真实取景器看不到自己的手)。
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void birdwatch$hideHandsInViewfinder(float partialTick, com.mojang.blaze3d.vertex.PoseStack poseStack,
		net.minecraft.client.renderer.SubmitNodeCollector submitNodeCollector,
		net.minecraft.client.player.LocalPlayer player, int light, CallbackInfo ci) {
		CameraSession session = CameraSession.get();
		if (session != null && session.isActive()) {
			ci.cancel();
		}
	}
}
