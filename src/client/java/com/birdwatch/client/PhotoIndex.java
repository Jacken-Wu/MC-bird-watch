package com.birdwatch.client;

import com.birdwatch.config.BirdWatchConfig;
import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 照片索引:扫描照片目录,JSON 元数据 + PNG 一一配对。
 * 相册 UI 的数据源(按文件名倒序 = 最新在前)。
 */
public final class PhotoIndex {
	private static final Gson GSON = new Gson();

	private PhotoIndex() {
	}

	@SuppressWarnings("unchecked")
	public static List<PhotoRecord> list() {
		Path root = com.birdwatch.client.photo.PhotoStorage.photosRoot();
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		List<PhotoRecord> result = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(json -> {
				try {
					Map<String, Object> data = GSON.fromJson(
						Files.readString(json, StandardCharsets.UTF_8), Map.class);
					String name = json.getFileName().toString().replace(".json", "");
					Path png = json.resolveSibling(name + ".png");
					if (Files.exists(png)) {
						result.add(new PhotoRecord(name, png, json, data));
					}
				} catch (IOException ignored) {
				}
			});
		} catch (IOException e) {
			return List.of();
		}
		result.sort(Comparator.comparing(PhotoRecord::name).reversed());
		return result;
	}

	public record PhotoRecord(String name, Path pngPath, Path jsonPath, Map<String, Object> data) {
	}
}
