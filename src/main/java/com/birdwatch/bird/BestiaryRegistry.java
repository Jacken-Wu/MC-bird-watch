package com.birdwatch.bird;

import com.birdwatch.BirdWatchMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 原版生物图鉴清单(M4b):扫描注册表覆盖全部原版可拍照生物。
 *
 * 26.2 无 EntityType.COW 之类静态常量(实体注册表驱动),启动时遍历
 * BuiltInRegistries.ENTITY_TYPE 收集:实体类为 LivingEntity 子类
 * (排除玩家/装饰物/非生物)且类别不是 MISCELLANEOUS 的条目。
 * 新版本原版生物自动收录,无需改清单。
 *
 * 条目顺序 = 注册表顺序 = 图鉴页序;显示名用 EntityType.getDescriptionId
 * (原版自带本地化,无需自备名称)。拍摄判定纯客户端:画面内实体类型
 * 在此清单 → 拍照即解锁(不评分)。
 */
public final class BestiaryRegistry {
	private static final Map<String, EntityType<?>> BY_ID = new LinkedHashMap<>();
	private static final Map<EntityType<?>, String> ID_BY_TYPE = new java.util.IdentityHashMap<>();

	private BestiaryRegistry() {
	}

	/**
	 * 惰性构建:注册表在实体类型注册后才完整(任何实体构造前调用均安全)。
	 * 收集条件:①实体类可实例化为 LivingEntity(排除玩家等特殊类型);
	 * ②类别非 MISCELLANEOUS(排除物品展示框/船/箭等非生物)。
	 */
	private static void ensureLoaded() {
		if (!BY_ID.isEmpty()) {
			return;
		}
		Registry<EntityType<?>> registry = BuiltInRegistries.ENTITY_TYPE;
		for (Map.Entry<ResourceKey<EntityType<?>>, EntityType<?>> entry : registry.entrySet()) {
			EntityType<?> type = entry.getValue();
			String id = entry.getKey().identifier().getPath();
			// 排除:非 minecraft 命名空间(本模组鸟)、玩家、非活体、杂项类别
			if (!entry.getKey().identifier().getNamespace().equals("minecraft")) {
				continue;
			}
			if ("player".equals(id)) {
				continue;
			}
			if (type.getCategory() == MobCategory.MISC) {
				continue;
			}
			try {
				if (!LivingEntity.class.isAssignableFrom(type.getBaseClass())) {
					continue;
				}
			} catch (Exception e) {
				continue;
			}
			BY_ID.put(id, type);
			ID_BY_TYPE.put(type, id);
		}
		BirdWatchMod.LOGGER.info("[BirdWatch] 生物图鉴收录 {} 种原版生物", BY_ID.size());
	}

	/** 全部生物 id(图鉴页序 = 注册表序) */
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
