package com.birdwatch.print;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.item.PhotoPrintItem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 印刷图服务端存储(M4b:印刷图从客户端移入服务端存档)。
 *
 * 印刷图 = 物品的「图像数据」:印刷时客户端上传缩放后的 PNG 字节,
 * 服务端写入 <世界存档>/birdwatch/prints/<printId>.png + 同名 json 元数据。
 * 物品 NBT 的 photo 字段存 printId,与文件一一绑定:
 * - 物品销毁(烧毁/爆炸 onDestroyed)、贴入图鉴消耗 → 服务端删文件;
 * - 旧照片返还 = 引用转移给新物品,文件保留;
 * - 自然消失(掉落物 5 分钟,不触发 onDestroyed)→ 周期 GC 兜底。
 * 防止存档无限变大。
 */
public final class PrintStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** printId 校验:UUID 前 8 位 hex,防路径穿越 */
	private static final Pattern PRINT_ID = Pattern.compile("[0-9a-f]{8}");
	/** GC 缓冲:无引用文件超过此时长才删除(防未加载区块容器中的物品被误删) */
	private static final Duration GC_AGE = Duration.ofHours(24);

	private PrintStore() {
	}

	/** 印刷图目录:<世界存档>/birdwatch/prints */
	public static Path printsDir(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("birdwatch").resolve("prints");
	}

	/**
	 * 保存印刷图:生成 printId(UUID 前 8 位 hex),写 png + json 元数据。
	 * 返回 printId;写入失败返回 null。
	 */
	public static String save(MinecraftServer server, byte[] pngBytes, String species, int score, String tier,
		String birdsJson) {
		String id;
		Path dir = printsDir(server);
		try {
			Files.createDirectories(dir);
			// 冲突重试:同 tick 内多次印刷 UUID 前缀可能重复
			for (int i = 0; i < 5; i++) {
				id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
				if (!Files.exists(dir.resolve(id + ".png"))) {
					Files.write(dir.resolve(id + ".png"), pngBytes);
					var meta = new java.util.LinkedHashMap<String, Object>();
					meta.put("species", species);
					meta.put("score", score);
					meta.put("tier", tier);
					if (birdsJson != null && !birdsJson.isBlank()) {
						meta.put("birds", GSON.fromJson(birdsJson, Object.class));
					}
					Files.writeString(dir.resolve(id + ".json"), GSON.toJson(meta), StandardCharsets.UTF_8);
					return id;
				}
			}
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[Print] 印刷图保存失败", e);
		}
		return null;
	}

	/** 读取印刷图 PNG;不存在或非法 id 返回空 */
	public static java.util.Optional<byte[]> readPng(MinecraftServer server, String printId) {
		if (!PRINT_ID.matcher(printId).matches()) {
			return java.util.Optional.empty();
		}
		Path png = printsDir(server).resolve(printId + ".png");
		try {
			return Files.exists(png) ? java.util.Optional.of(Files.readAllBytes(png))
				: java.util.Optional.empty();
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[Print] 印刷图读取失败 {}", printId, e);
			return java.util.Optional.empty();
		}
	}

	/** 从印刷图元数据 json 读评分;失败返回 0 */
	public static int readScore(MinecraftServer server, String printId) {
		if (!PRINT_ID.matcher(printId).matches()) {
			return 0;
		}
		Path json = printsDir(server).resolve(printId + ".json");
		try {
			if (Files.exists(json)) {
				var obj = com.google.gson.JsonParser.parseString(Files.readString(json)).getAsJsonObject();
				if (obj.has("score")) {
					return obj.get("score").getAsInt();
				}
			}
		} catch (Exception e) {
			BirdWatchMod.LOGGER.error("[Print] 印刷图元数据读取失败 {}", printId, e);
		}
		return 0;
	}

	/** 删除印刷图(png + json);非法 id 拒绝;文件不存在视为成功 */
	public static boolean delete(MinecraftServer server, String printId) {
		if (!PRINT_ID.matcher(printId).matches()) {
			return false;
		}
		Path dir = printsDir(server);
		boolean ok = true;
		try {
			Files.deleteIfExists(dir.resolve(printId + ".png"));
			Files.deleteIfExists(dir.resolve(printId + ".json"));
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[Print] 印刷图删除失败 {}", printId, e);
			ok = false;
		}
		return ok;
	}

	// ------------------------------------------------------------------
	// GC:删除「无引用且创建超过 24h」的印刷图
	// ------------------------------------------------------------------

	/**
	 * 周期清理(服务端,每 10 分钟):
	 * 收集全服引用 = 在线玩家背包 + 末影箱 + 已加载 ItemEntity/ItemFrame 中的印刷物品 printId;
	 * 删除无引用且文件 mtime 超过 GC_AGE 的文件。
	 * 24h 缓冲:未加载区块容器里的物品引用收集不到,缓冲期内不会被误删;
	 * 长期无人触碰(未加载区块中的印刷物品)才会被清理 —— 与「物品销毁即删」的目标一致。
	 */
	public static void gc(ServerLevel level) {
		Path dir = printsDir(level.getServer());
		if (!Files.isDirectory(dir)) {
			return;
		}
		Set<String> referenced = new HashSet<>();
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			collectFromInventory(player.getInventory(), referenced);
			collectFromInventory(player.getEnderChestInventory(), referenced);
		}
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof ItemEntity item) {
				collectFromStack(item.getItem(), referenced);
			} else if (entity instanceof ItemFrame frame) {
				collectFromStack(frame.getItem(), referenced);
			}
		}
		Instant cutoff = Instant.now().minus(GC_AGE);
		try (Stream<Path> files = Files.list(dir)) {
			files.filter(p -> p.getFileName().toString().endsWith(".png")).forEach(png -> {
				String id = png.getFileName().toString().replace(".png", "");
				if (referenced.contains(id)) {
					return;
				}
				try {
					if (Files.getLastModifiedTime(png).toInstant().isBefore(cutoff)) {
						Files.deleteIfExists(png);
						Files.deleteIfExists(dir.resolve(id + ".json"));
						BirdWatchMod.LOGGER.info("[Print] GC 清理无引用印刷图 {}", id);
					}
				} catch (IOException e) {
					BirdWatchMod.LOGGER.error("[Print] GC 清理失败 {}", id, e);
				}
			});
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[Print] GC 扫描失败", e);
		}
	}

	private static void collectFromInventory(net.minecraft.world.Container container, Set<String> out) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			collectFromStack(container.getItem(i), out);
		}
	}

	private static void collectFromStack(ItemStack stack, Set<String> out) {
		if (stack.isEmpty() || !stack.is(com.birdwatch.registry.ModItems.PHOTO_PRINT)) {
			return;
		}
		var data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return;
		}
		String id = data.copyTag().getString(PhotoPrintItem.KEY_PHOTO).orElse("");
		if (PRINT_ID.matcher(id).matches()) {
			out.add(id);
		}
	}
}
