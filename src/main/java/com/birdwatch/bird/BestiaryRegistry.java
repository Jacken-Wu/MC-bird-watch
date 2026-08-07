package com.birdwatch.bird;

import com.birdwatch.BirdWatchMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 原版生物图鉴清单(M4b):常见可拍照生物。
 *
 * 26.2 无 EntityType.COW 之类静态常量(实体注册表驱动),统一按注册表 ID
 * 运行时查询 BuiltInRegistries.ENTITY_TYPE.getValue(id)(与 ModEntities 注册对称)。
 * 拍摄判定纯客户端:画面内实体类型在此清单 → 拍照即解锁(不评分)。
 *
 * 条目顺序 = 图鉴页序;显示名用 EntityType.getDescriptionId 的翻译键
 * (原版自带本地化,无需自备名称)。
 */
public final class BestiaryRegistry {
	/** 常见生物 id 清单(26.2 注册表 id,按图鉴展示顺序) */
	private static final List<String> ENTITY_IDS = List.of(
		// 家畜与被动
		"cow", "sheep", "pig", "chicken",
		"horse", "donkey", "rabbit", "cat",
		"wolf", "fox", "goat", "bat",
		"parrot", "axolotl", "frog", "turtle",
		// 水生
		"squid", "cod", "salmon", "tropical_fish",
		// 敌对(拍照也能拍到)
		"zombie", "skeleton", "spider", "creeper"
	);

	private static final Map<String, EntityType<?>> BY_ID = new LinkedHashMap<>();
	private static final Map<EntityType<?>, String> ID_BY_TYPE = new java.util.IdentityHashMap<>();

	private BestiaryRegistry() {
	}

	/** 惰性构建:注册表在实体类型注册后才完整(任何实体构造前调用均安全) */
	private static void ensureLoaded() {
		if (!BY_ID.isEmpty()) {
			return;
		}
		for (String id : ENTITY_IDS) {
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(
				Identifier.fromNamespaceAndPath("minecraft", id));
			if (type != null) {
				BY_ID.put(id, type);
				ID_BY_TYPE.put(type, id);
			} else {
				BirdWatchMod.LOGGER.warn("[BirdWatch] 生物图鉴:未找到实体 {}", id);
			}
		}
	}

	/** 全部生物 id(图鉴页序) */
	public static List<String> allIds() {
		ensureLoaded();
		return List.copyOf(BY_ID.keySet());
	}

	/** 实体类型 → 图鉴 id;非图鉴生物返回空 */
	public static Optional<String> idOf(EntityType<?> type) {
		ensureLoaded();
		return Optional.ofNullable(ID_BY_TYPE.get(type));
	}

	/** 图鉴 id → 实体类型 */
	public static Optional<EntityType<?>> typeOf(String id) {
		ensureLoaded();
		return Optional.ofNullable(BY_ID.get(id));
	}
}
