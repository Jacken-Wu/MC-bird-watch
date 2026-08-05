package com.birdwatch.client.mixin;

import com.birdwatch.client.CameraSession;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DoF 后处理时机:GameRenderer.render 中,世界渲染完成后、HUD 渲染前
 * (与原版后处理链同一位置)执行景深后处理 —— 此时主渲染目标已有世界内容。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/**
	 * 世界渲染完成后、HUD 渲染前全屏拷贝虚化画面:
	 * 拷贝后 GuiRenderer.render() 把取景器 UI 画在最上层,不会被覆盖。
	 */
	@Inject(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V",
		shift = At.Shift.BEFORE))
	private void birdwatch$blitDofBeforeHud(DeltaTracker deltaTracker, boolean bl, CallbackInfo ci) {
		CameraSession session = CameraSession.get();
		if (session != null) {
			session.blitDofToMain();
		}
	}
}
