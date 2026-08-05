package com.birdwatch.client.mixin;

import com.birdwatch.client.CameraSession;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 取景器内按 ESC 会触发暂停菜单(Gui.setPauseScreen)。
 * 取景器激活时:拦截暂停并退出取景器。
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method = "setPauseScreen", at = @At("HEAD"), cancellable = true)
	private void birdwatch$blockPauseInViewfinder(boolean pause, boolean dueToTimeout, CallbackInfo ci) {
		CameraSession session = CameraSession.get();
		if (session != null && session.isActive()) {
			session.exitViewfinder();
			ci.cancel();
		}
	}
}
