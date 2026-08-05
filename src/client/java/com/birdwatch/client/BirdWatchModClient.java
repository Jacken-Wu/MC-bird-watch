package com.birdwatch.client;

import com.birdwatch.BirdWatchMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

import java.util.List;

public class BirdWatchModClient implements ClientModInitializer {
	/** 取景器激活时隐藏的原版 HUD 元素 */
	private static final List<Identifier> HIDDEN_IN_VIEWFINDER = List.of(
		VanillaHudElements.HOTBAR,
		VanillaHudElements.CROSSHAIR,
		VanillaHudElements.HEALTH_BAR,
		VanillaHudElements.FOOD_BAR,
		VanillaHudElements.ARMOR_BAR,
		VanillaHudElements.AIR_BAR,
		VanillaHudElements.EXPERIENCE_LEVEL,
		VanillaHudElements.HELD_ITEM_TOOLTIP,
		VanillaHudElements.INFO_BAR,
		VanillaHudElements.MOUNT_HEALTH,
		VanillaHudElements.MOB_EFFECTS,
		VanillaHudElements.BOSS_BAR,
		VanillaHudElements.MISC_OVERLAYS
	);

	@Override
	public void onInitializeClient() {
		CameraSession.init();
		ViewfinderHud.register();
		MenuScreensRegistry.register();
		hideVanillaHudInViewfinder();
	}

	/** 用条件包装器替换原版 HUD 元素:取景器激活时不绘制,其余时间原样透传 */
	private static void hideVanillaHudInViewfinder() {
		for (Identifier id : HIDDEN_IN_VIEWFINDER) {
			HudElementRegistry.replaceElement(id, original -> (graphics, tracker) -> {
				if (!CameraSession.isViewfinderActive()) {
					original.extractRenderState(graphics, tracker);
				}
			});
		}
	}
}
