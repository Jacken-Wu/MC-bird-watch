package com.birdwatch.camera;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 镜头数据注册表。物品 ID 与镜头 ID 一一对应(birdwatch:lens_50mm ↔ "lens_50mm")。
 */
public final class LensRegistry {
	public static final List<LensDefinition> LENSES = List.of(
		new LensDefinition("lens_24mm", "item.birdwatch.lens_24mm", LensDefinition.LensType.PRIME,
			24, 24, 2.8f, 16f, true, 15),
		new LensDefinition("lens_50mm", "item.birdwatch.lens_50mm", LensDefinition.LensType.PRIME,
			50, 50, 1.8f, 16f, true, 20),
		new LensDefinition("lens_200mm", "item.birdwatch.lens_200mm", LensDefinition.LensType.PRIME,
			200, 200, 4f, 22f, true, 30),
		new LensDefinition("lens_400mm", "item.birdwatch.lens_400mm", LensDefinition.LensType.PRIME,
			400, 400, 5.6f, 22f, true, 40),
		new LensDefinition("lens_zoom_70_300", "item.birdwatch.lens_zoom_70_300", LensDefinition.LensType.ZOOM,
			70, 300, 4f, 11f, true, 35)
	);

	private static final Map<String, LensDefinition> BY_ID = LENSES.stream()
		.collect(Collectors.toMap(LensDefinition::id, Function.identity()));

	/** 标准光圈档位(整档) */
	public static final float[] APERTURE_STOPS = {1.4f, 1.8f, 2f, 2.8f, 4f, 5.6f, 8f, 11f, 16f, 22f};

	private LensRegistry() {
	}

	/** 按 ID 查镜头;空或未知 ID 返回 null(无镜头状态) */
	public static LensDefinition byId(String id) {
		if (id == null || id.isEmpty()) {
			return null;
		}
		return BY_ID.get(id);
	}

	/** 由镜头物品反查镜头定义(物品 ID 去掉命名空间即为镜头 ID) */
	public static LensDefinition byItem(Item item) {
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		return byId(path);
	}

	public static boolean isLensItem(Item item) {
		return BY_ID.containsKey(BuiltInRegistries.ITEM.getKey(item).getPath());
	}

	/** 镜头定义光圈范围内可用的标准档位 */
	public static float[] apertureStops(LensDefinition lens) {
		int from = 0;
		int to = APERTURE_STOPS.length;
		while (from < to && APERTURE_STOPS[from] < lens.minAperture() - 0.001f) from++;
		while (to > from && APERTURE_STOPS[to - 1] > lens.maxAperture() + 0.001f) to--;
		if (from >= to) return new float[]{lens.minAperture()};
		float[] result = new float[to - from];
		System.arraycopy(APERTURE_STOPS, from, result, 0, to - from);
		return result;
	}
}
