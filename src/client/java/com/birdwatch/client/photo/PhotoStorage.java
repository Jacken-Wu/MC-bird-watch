package com.birdwatch.client.photo;

import com.birdwatch.config.BirdWatchConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/**
 * 照片存储位置(客户端):照片按世界存档隔离。
 * 单人游戏 → &lt;世界存档&gt;/&lt;photoDirectory&gt;;多人/未进世界回退 .minecraft/&lt;photoDirectory&gt;。
 * 所有照片读写(拍照/印刷/相册/图鉴/展示框渲染)统一走本类,保证跨存档独立。
 */
public final class PhotoStorage {
	public static Path photosRoot() {
		return base().resolve(BirdWatchConfig.photoDirectory);
	}

	/**
	 * 解析照片路径(读取):优先世界存档根,找不到回退旧根 .minecraft/&lt;photoDirectory&gt;。
	 * 存档隔离改造前的旧印刷照片/图鉴记录路径相对旧根,回退保证兼容。
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

	/** 存档隔离基目录:单人游戏用当前世界存档目录 */
	private static Path base() {
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer server = mc.getSingleplayerServer();
		if (server != null) {
			return server.getWorldPath(LevelResource.ROOT);
		}
		return FabricLoader.getInstance().getGameDir();
	}

	private PhotoStorage() {
	}
}
