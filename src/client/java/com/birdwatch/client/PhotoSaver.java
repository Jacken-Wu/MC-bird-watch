package com.birdwatch.client;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.config.BirdWatchConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 照片存档:PNG + JSON 元数据。
 * M1 无鸟类判定,全部归档到 photos/未收录/;M2 起按主体鸟分子目录。
 */
public final class PhotoSaver {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private PhotoSaver() {
	}

	public static void save(NativeImage image, CameraSession.PhotoData data) {
		NativeImage cropped = cropViewfinder(image);
		Path dir = FabricLoader.getInstance().getGameDir()
			.resolve(BirdWatchConfig.photoDirectory)
			.resolve("未收录");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 无法创建照片目录 {}", dir, e);
			cropped.close();
			return;
		}

		String base = LocalDateTime.now().format(NAME_FORMAT);
		Path png = dir.resolve(base + ".png");
		Path json = dir.resolve(base + ".json");
		int counter = 1;
		while (Files.exists(png)) {
			png = dir.resolve(base + "_" + (counter++) + ".png");
			json = dir.resolve(base + "_" + (counter - 1) + ".json");
		}

		try {
			cropped.writeToFile(png);
			Files.writeString(json, GSON.toJson(toMap(data)), StandardCharsets.UTF_8);
			BirdWatchMod.LOGGER.info("[BirdWatch] 照片已保存:{}", png);
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 照片写入失败", e);
		} finally {
			cropped.close();
		}
	}

	/**
	 * 裁剪取景器黑色边框(四周 16px + 顶部条 12px + 底部条 12px),
	 * 使照片内容与取景器看到的画面一致(不含 UI)。
	 */
	private static NativeImage cropViewfinder(NativeImage image) {
		double scale = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
		int sx = (int) (16 * scale);
		int syTop = (int) (15 * scale);
		int syBottom = (int) (15 * scale);
		int dw = image.getWidth() - sx * 2;
		int dh = image.getHeight() - syTop - syBottom;
		if (dw <= 0 || dh <= 0 || (sx == 0 && syTop == 0 && syBottom == 0)) {
			return image;
		}
		NativeImage cropped = new NativeImage(dw, dh, true);
		for (int y = 0; y < dh; y++) {
			for (int x = 0; x < dw; x++) {
				cropped.setPixel(x, y, image.getPixel(x + sx, y + syTop));
			}
		}
		image.close();
		return cropped;
	}

	private static Map<String, Object> toMap(CameraSession.PhotoData data) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("lensId", data.lensId());
		map.put("focalLength", data.focalLength());
		map.put("aperture", data.aperture());
		map.put("shutter", data.shutter());
		map.put("iso", data.iso());
		map.put("focusDistance", data.focusDistance());
		map.put("targetDistance", data.targetDistance());
		map.put("fov", data.fov());
		map.put("species", new String[0]); // M2:画面内鸟种
		return map;
	}
}
