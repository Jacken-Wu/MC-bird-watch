package com.birdwatch.config;

import com.birdwatch.BirdWatchMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组配置文件(config/birdwatch.json)。
 * 首次启动写入默认值;字段缺失时沿用内存默认,保证旧配置兼容新增项。
 */
public final class BirdWatchConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 调试开关:输出拍照评分等调试信息 */
	public static boolean debug = false;

	/** 照片存储目录(相对 .minecraft),M1 相机里程碑使用 */
	public static String photoDirectory = "birdwatch/photos";

	/** 照片根目录(绝对路径):.minecraft/<photoDirectory> */
	public static Path photosRoot() {
		return FabricLoader.getInstance().getGameDir().resolve(photoDirectory);
	}

	private BirdWatchConfig() {
	}

	public static void load() {
		Path configFile = FabricLoader.getInstance().getConfigDir().resolve("birdwatch.json");
		if (Files.exists(configFile)) {
			try {
				Data data = GSON.fromJson(Files.readString(configFile, StandardCharsets.UTF_8), Data.class);
				if (data != null) {
					debug = data.debug;
					if (data.photoDirectory != null && !data.photoDirectory.isBlank()) {
						photoDirectory = data.photoDirectory;
					}
				}
			} catch (Exception e) {
				BirdWatchMod.LOGGER.error("[BirdWatch] 配置文件解析失败,使用默认值", e);
			}
		} else {
			saveDefault(configFile);
		}
	}

	private static void saveDefault(Path configFile) {
		Data defaults = new Data();
		defaults.debug = debug;
		defaults.photoDirectory = photoDirectory;
		try {
			Files.createDirectories(configFile.getParent());
			Files.writeString(configFile, GSON.toJson(defaults), StandardCharsets.UTF_8);
			BirdWatchMod.LOGGER.info("[BirdWatch] 已生成默认配置:{}", configFile);
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 写入默认配置失败", e);
		}
	}

	private static class Data {
		public boolean debug;
		public String photoDirectory;
	}
}
