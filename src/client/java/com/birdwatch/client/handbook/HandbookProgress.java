package com.birdwatch.client.handbook;

import com.birdwatch.BirdWatchMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 图鉴进度持久化(客户端本地,休闲定位):
 * gameDir/birdwatch/handbook.json —— 每物种:解锁状态 + 槽位照片引用。
 * 最高分/档位由照片目录元数据实时推导(照片数据是共享数据源)。
 */
public final class HandbookProgress {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Entry> ENTRIES = new HashMap<>();
	private static boolean dirty;

	private HandbookProgress() {
	}

	/** 单物种进度;crop 为槽位照片的印刷裁剪矩形(归一化 x,y,w,h 字符串) */
	public record Entry(boolean unlocked, String slotPhoto, String crop) {
	}

	public static void load() {
		ENTRIES.clear();
		Path file = file();
		if (!Files.exists(file)) {
			return;
		}
		try {
			Map<String, Map<String, Object>> raw = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
				new com.google.gson.reflect.TypeToken<Map<String, Map<String, Object>>>() {
				}.getType());
			if (raw == null) {
				return;
			}
			raw.forEach((species, data) -> {
				boolean unlocked = Boolean.TRUE.equals(data.get("unlocked"));
				String slot = data.get("slotPhoto") instanceof String s ? s : null;
				String crop = data.get("crop") instanceof String c ? c : null;
				ENTRIES.put(species, new Entry(unlocked, slot, crop));
			});
		} catch (Exception e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 图鉴进度读取失败", e);
		}
	}

	public static boolean isUnlocked(String speciesId) {
		Entry e = ENTRIES.get(speciesId);
		return e != null && e.unlocked();
	}

	public static String slotPhoto(String speciesId) {
		Entry e = ENTRIES.get(speciesId);
		return e != null ? e.slotPhoto() : null;
	}

	public static String slotCrop(String speciesId) {
		Entry e = ENTRIES.get(speciesId);
		return e != null ? e.crop() : null;
	}

	/** 贴入印刷照片解锁(评分 ≥60 由调用方判定);photoPath 为 photos 目录相对路径 */
	public static void unlock(String speciesId, String photoPath, String crop) {
		ENTRIES.put(speciesId, new Entry(true, photoPath, crop));
		dirty = true;
	}

	public static void save() {
		if (!dirty) {
			return;
		}
		try {
			Path file = file();
			Files.createDirectories(file.getParent());
			Map<String, Object> out = new HashMap<>();
			ENTRIES.forEach((species, e) -> {
				Map<String, Object> m = new HashMap<>();
				m.put("unlocked", e.unlocked());
				if (e.slotPhoto() != null) {
					m.put("slotPhoto", e.slotPhoto());
				}
				if (e.crop() != null) {
					m.put("crop", e.crop());
				}
				out.put(species, m);
			});
			Files.writeString(file, GSON.toJson(out), StandardCharsets.UTF_8);
			dirty = false;
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 图鉴进度写入失败", e);
		}
	}

	private static Path file() {
		return FabricLoader.getInstance().getGameDir()
			.resolve(com.birdwatch.config.BirdWatchConfig.photoDirectory).resolveSibling("handbook.json");
	}
}
