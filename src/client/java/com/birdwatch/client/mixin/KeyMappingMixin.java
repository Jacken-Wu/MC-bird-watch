package com.birdwatch.client.mixin;

import com.birdwatch.client.CameraSession;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 取景器输入隔离:在按键状态更新的源头(KeyMapping.click/set)拦截,
 * 取景器激活时吞掉无关按键的按下:1-9 物品栏、攻击(左键)、背包(E)、丢弃(Q)、副手(F)。
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {
	private static boolean birdwatch$isBlockedKey(InputConstants.Key key) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options == null) {
			return false;
		}
		for (KeyMapping mapping : mc.options.keyHotbarSlots) {
			if (key.equals(KeyMappingHelper.getBoundKeyOf(mapping))) {
				return true;
			}
		}
		return key.equals(KeyMappingHelper.getBoundKeyOf(mc.options.keyAttack))
			|| key.equals(KeyMappingHelper.getBoundKeyOf(mc.options.keyInventory))
			|| key.equals(KeyMappingHelper.getBoundKeyOf(mc.options.keyDrop))
			|| key.equals(KeyMappingHelper.getBoundKeyOf(mc.options.keySwapOffhand));
	}

	@Inject(method = "click", at = @At("HEAD"), cancellable = true)
	private static void birdwatch$blockClick(InputConstants.Key key, CallbackInfo ci) {
		CameraSession session = CameraSession.get();
		if (session != null && session.isActive() && birdwatch$isBlockedKey(key)) {
			ci.cancel();
		}
	}

	@Inject(method = "set", at = @At("HEAD"), cancellable = true)
	private static void birdwatch$blockPress(InputConstants.Key key, boolean pressed, CallbackInfo ci) {
		CameraSession session = CameraSession.get();
		if (session != null && session.isActive() && pressed && birdwatch$isBlockedKey(key)) {
			ci.cancel();
		}
	}
}
