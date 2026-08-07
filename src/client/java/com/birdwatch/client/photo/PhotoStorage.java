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
	 * 解析照片路径(读取):优先当前世界照片根,找不到回退旧根 .minecraft/&lt;photoDirectory&gt;。
	 * 世界分目录改造前的旧照片/图鉴记录路径相对旧根,回退保证兼容。
	 */
	public static Path resolvePhoto(String photoPath) {
		Path worldPath = photosRoot().resolve(photoPath);
		if (java.nio.file.Files.exists(worldPath)) {
			return worldPath;
		}
		Path legacyPath = FabricLoader.getInstance().getGameDir()
			.resolve(BirdWatchConfig.photoDirectory).resolve(photoPath);
		return java.nio.file.Files.exists(legacyPath) ? legacyPath : worldPath;
	}

	/** 旧照片根(世界分目录改造前的 .minecraft/birdwatch/photos/,兼容回退用) */
	public static Path legacyRoot() {
		return FabricLoader.getInstance().getGameDir().resolve(BirdWatchConfig.photoDirectory);
	}

	/**
	 * 照片基目录 = gameDir/birdwatch/photos/&lt;世界标识&gt;/。
	 * 世界标识:单机取世界存档目录名(不同存档照片分开);
	 * 多人取服务器 ip(净化为 [a-z0-9_]);未进世界 → "local"。
	 */
	private static Path base() {
		Minecraft mc = Minecraft.getInstance();
		String worldKey;
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
			worldKey = mc.getSingleplayerServer().getWorldPath(
				net.minecraft.world.level.storage.LevelResource.ROOT).getFileName().toString();
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
