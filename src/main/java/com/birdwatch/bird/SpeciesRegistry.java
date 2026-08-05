package com.birdwatch.bird;

import com.birdwatch.entity.HeronEntity;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

/**
 * 物种注册表(M2a:白鹭定调;M4 扩展其余 11 种)。
 *
 * speciesId 同时用作:照片元数据标识、图鉴条目 key、成就触发器参数;
 * directoryName 为照片归档目录(zh 主语言,如「白鹭」)。
 */
public final class SpeciesRegistry {
	public static final String HERON_ID = "heron";

	/** 实体 → 物种 id;非观鸟模组实体返回空 */
	public static Optional<String> speciesIdOf(Entity entity) {
		if (entity instanceof HeronEntity) {
			return Optional.of(HERON_ID);
		}
		return Optional.empty();
	}

	/** 物种 id → 照片归档目录名(无中文名回退 id) */
	public static String directoryName(String speciesId) {
		return switch (speciesId) {
			case HERON_ID -> "白鹭";
			default -> speciesId;
		};
	}

	private SpeciesRegistry() {
	}
}
