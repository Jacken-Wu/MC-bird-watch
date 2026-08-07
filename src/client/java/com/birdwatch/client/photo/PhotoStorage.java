package com.birdwatch.client.photo;

import com.birdwatch.config.BirdWatchConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * 照片存储位置(客户端):照片恒存客户端本地,按世界标识分目录 ——
 * 多人服务器上各玩家照片存在各自客户端,天然隔离。
 * 世界标识:单机 = 世界存档名;多人 = 服务器 ip(净化);未进世界 = "local"。
 * 印刷图不在此处:印刷图存服务端存档(PrintStore),客户端仅缓存到 print_cache/。
 */
public final class PhotoStorage {
	/** 照片根 = gameDir/birdwatch/photos/&lt;世界标识&gt;/(photoDirectory 配置值已含在此路径中) */
	public static Path photosRoot() {
		return base();
	}

	/** 印刷图客户端缓存目录(渲染按需请求服务端后落盘) */
	public static Path printCacheRoot() {
		return photosRoot().resolve("print_cache");
	}

	/**
	 * 解析照片路径(读取):优先当前世界照片根,找不到按旧架构位置回退:
	 * ① 世界分目录改造前单人照片存在世界存档 &lt;存档&gt;/birdwatch/photos/;
	 * ② 更早版本(无存档隔离)在 gameDir/birdwatch/photos/。
	 * 回退保证旧印刷物品/图鉴记录兼容。
	 */
	public static Path resolvePhoto(String photoPath) {
		Path worldPath = photosRoot().resolve(photoPath);
		if (java.nio.file.Files.exists(worldPath)) {
			return worldPath;
		}
		Path savePath = savePhotosRoot().resolve(photoPath);
		if (java.nio.file.Files.exists(savePath)) {
			return savePath;
		}
		Path legacyPath = FabricLoader.getInstance().getGameDir()
			.resolve(BirdWatchConfig.photoDirectory).resolve(photoPath);
		return java.nio.file.Files.exists(legacyPath) ? legacyPath : worldPath;
	}

	/** 旧照片根(世界分目录改造前的 gameDir/birdwatch/photos/,兼容回退用) */
	public static Path legacyRoot() {
		return FabricLoader.getInstance().getGameDir().resolve(BirdWatchConfig.photoDirectory);
	}

	/** 世界存档内旧照片根(改造前单人照片位置,兼容回退用) */
	private static Path savePhotosRoot() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
			return mc.getSingleplayerServer().getWorldPath(
				net.minecraft.world.level.storage.LevelResource.ROOT)
				.resolve(BirdWatchConfig.photoDirectory).normalize();
		}
		return legacyRoot();
	}

	/**
	 * 照片基目录 = gameDir/birdwatch/photos/&lt;世界标识&gt;/。
	 * 世界标识:单机取世界存档目录名(不同存档照片分开);
	 * 多人取服务器 ip(净化为 [a-z0-9_]);未进世界 → "local"。
	 * 注意:getWorldPath(LevelResource.ROOT) 返回 &lt;存档&gt;/. (ROOT 定义为 "."),
	 * 必须 normalize() 去除 /. 后缀再取目录名,否则世界标识为 "."(实测踩坑)。
	 */
	private static Path base() {
		Minecraft mc = Minecraft.getInstance();
		String worldKey;
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
			worldKey = mc.getSingleplayerServer().getWorldPath(
				net.minecraft.world.level.storage.LevelResource.ROOT)
				.normalize().getFileName().toString();
		} else if (mc.getCurrentServer() != null) {
			worldKey = mc.getCurrentServer().ip.replaceAll("[^a-zA-Z0-9]", "_");
		} else {
			worldKey = "local";
		}
		return FabricLoader.getInstance().getGameDir()
			.resolve("birdwatch").resolve("photos").resolve(worldKey);
	}

	private PhotoStorage() {
	}
}
