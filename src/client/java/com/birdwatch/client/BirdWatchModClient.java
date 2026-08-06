package com.birdwatch.client;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.bird.BirdSpecies;
import com.birdwatch.bird.SpeciesRegistry;
import com.birdwatch.client.entity.BirdGeoRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
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
		registerEntityRenderers();
		registerPhotoPrintSpecialModel();
	}

	/** 全物种 GeckoLib 渲染器注册(M4a 泛化:共用 BirdGeoRenderer,模型按物种取资源) */
	private static void registerEntityRenderers() {
		for (BirdSpecies species : SpeciesRegistry.all()) {
			EntityRendererRegistry.register(SpeciesRegistry.entityType(species),
				ctx -> new BirdGeoRenderer(ctx, species));
		}
	}

	/** 印刷照片特殊模型注册(反射:原版 ID_MAPPER 为私有,无公开 API) */
	public static void registerPhotoPrintSpecialModel() {
		try {
			java.lang.reflect.Field field = net.minecraft.client.renderer.special.SpecialModelRenderers.class
				.getDeclaredField("ID_MAPPER");
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			net.minecraft.util.ExtraCodecs.LateBoundIdMapper<net.minecraft.resources.Identifier,
				com.mojang.serialization.MapCodec<? extends net.minecraft.client.renderer.special.SpecialModelRenderer.Unbaked<?>>> mapper
				= (net.minecraft.util.ExtraCodecs.LateBoundIdMapper) field.get(null);
			mapper.put(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "photo_print"),
				com.birdwatch.client.photo.PhotoPrintSpecialModel.MAP_CODEC);
			BirdWatchMod.LOGGER.info("[BirdWatch] 印刷照片特殊模型已注册");
		} catch (Exception e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 印刷照片特殊模型注册失败", e);
		}
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
