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
		registerPrintImageReceiver();
		registerBestiaryReceiver();
	}

	/** 生物图鉴状态回传接收:更新客户端解锁视图 */
	private static void registerBestiaryReceiver() {
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
			com.birdwatch.network.ModNetworking.BESTIARY_STATE,
			(payload, context) -> context.client().execute(() ->
				com.birdwatch.client.handbook.BestiaryProgress.apply(payload.unlocked())));
	}

	/** 印刷图下发接收:写客户端 print_cache(渲染缓存),供印刷物品/图鉴槽位渲染 */
	private static void registerPrintImageReceiver() {
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
			com.birdwatch.network.ModNetworking.PRINT_IMAGE,
			(payload, context) -> {
				context.client().execute(() -> {
					try {
						java.nio.file.Path cache = com.birdwatch.client.photo.PhotoStorage.printCacheRoot();
						java.nio.file.Files.createDirectories(cache);
						java.nio.file.Files.write(cache.resolve(payload.printId() + ".png"), payload.pngBytes());
					} catch (java.io.IOException e) {
						BirdWatchMod.LOGGER.error("[Print] 印刷图缓存写入失败 {}", payload.printId(), e);
					}
				});
			});
		// 印刷图删除通知:服务端文件已删(物品销毁等)→ 客户端同步清 print_cache,
		// 防客户端缓存无限增长(缓存可重建,缺失时按需重新请求)
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
			com.birdwatch.network.ModNetworking.PRINT_IMAGE_DELETE,
			(payload, context) -> {
				context.client().execute(() -> {
					try {
						java.nio.file.Files.deleteIfExists(
							com.birdwatch.client.photo.PhotoStorage.printCacheRoot()
								.resolve(payload.printId() + ".png"));
					} catch (java.io.IOException e) {
						BirdWatchMod.LOGGER.error("[Print] 印刷图缓存删除失败 {}", payload.printId(), e);
					}
				});
			});
		// 周期清理超龄 print_cache(兜底:创造模式删除物品/掉落物自然消失无删除通知,
		// 服务端 GC 删文件但客户端缓存残留;超过 7 天未触碰的缓存视为可弃)
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.level == null || ++cacheGcCooldown % 12000 != 0) {
				return;
			}
			try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(
				com.birdwatch.client.photo.PhotoStorage.printCacheRoot())) {
				long cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
				files.filter(p -> p.getFileName().toString().endsWith(".png")).forEach(p -> {
					try {
						if (java.nio.file.Files.getLastModifiedTime(p).toMillis() < cutoff) {
							java.nio.file.Files.deleteIfExists(p);
							BirdWatchMod.LOGGER.info("[Print] 清理超龄印刷图缓存 {}", p.getFileName());
						}
					} catch (java.io.IOException e) {
						BirdWatchMod.LOGGER.error("[Print] 超龄缓存清理失败 {}", p.getFileName(), e);
					}
				});
			} catch (java.io.IOException ignored) {
			}
		});
	}

	/** print_cache 周期清理计数(每 12000 tick = 10 分钟检查一次) */
	private static int cacheGcCooldown;

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
