package com.birdwatch.client.mixin;

import com.birdwatch.client.CameraSession;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 取景器 FOV 覆盖:焦距 → 视场角(400mm ≈ 10°)。
 * 拦截 calculateFov 的返回值 —— 该值写入 Camera.fov 字段,
 * 所有下游读取方(世界投影/手持渲染)都会拿到覆盖值。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void birdwatch$overrideFov(float partialTick, CallbackInfoReturnable<Float> cir) {
		CameraSession session = CameraSession.get();
		if (session != null && session.isActive()) {
			float fov = session.computeFov();
			if (fov > 0) {
				cir.setReturnValue(fov);
			}
		}
	}
}
