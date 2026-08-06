package com.birdwatch.bird;

import com.birdwatch.entity.BirdEntity;
import com.birdwatch.entity.LittleEgretEntity;
import com.birdwatch.entity.SparrowEntity;
import com.birdwatch.entity.TitEntity;
import com.birdwatch.registry.ModSounds;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 物种注册表(M2a:白鹭定调;M4a:数据驱动重构 —— 实体类型索引 + 物种查询)。
 *
 * speciesId 同时用作:照片元数据标识、图鉴条目 key、成就触发器参数;
 * directoryName 为照片归档目录(zh 主语言,如「白鹭」)。
 *
 * 实体类型在 {@code ModEntities.registerAll()} 中按物种构建并调用 {@link #indexType}
 * 双向登记,实体实例 → 物种查询走 IdentityHashMap(类型查表,零反射零 instanceof)。
 */
public final class SpeciesRegistry {
	/** 小白鹭(Egretta garzetta)—— 湿地涉禽,定调物种 */
	public static final BirdSpecies LITTLE_EGRET = new BirdSpecies(
		"little_egret", "白鹭", LittleEgretEntity.class, BirdSpecies.Habitat.WETLAND,
		true, 0.5f, 0.9f,
		12.0, 0.25, 0.4, 24.0,
		8.0, 4.0, 8.0, 12.0, 20.0,
		80.0, 8.0,
		0.3F, 0.8F, 40.0, 80.0,
		true,
		ModSounds.LITTLE_EGRET_AMBIENT, ModSounds.LITTLE_EGRET_SCARED,
		ModSounds.LITTLE_EGRET_HURT, ModSounds.LITTLE_EGRET_DEATH, ModSounds.LITTLE_EGRET_FLAP,
		"little_egret",
		List.of(Biomes.SWAMP, Biomes.MANGROVE_SWAMP, Biomes.RIVER, Biomes.BEACH),
		12, 1, 3);

	/** 树麻雀(Passer montanus)—— 村落/聚落常见小鸟 */
	public static final BirdSpecies SPARROW = new BirdSpecies(
		"sparrow", "麻雀", SparrowEntity.class, BirdSpecies.Habitat.VILLAGE,
		true, 0.25f, 0.35f,
		4.0, 0.22, 0.5, 16.0,
		6.0, 3.0, 5.0, 8.0, 14.0,
		0.0, 0.0,
		0.15F, 0.3F, 8.0, 20.0,
		false,
		ModSounds.SPARROW_AMBIENT, ModSounds.SPARROW_SCARED,
		ModSounds.SPARROW_HURT, ModSounds.SPARROW_DEATH, ModSounds.SPARROW_FLAP,
		"sparrow",
		List.of(Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.MEADOW,
			Biomes.FOREST, Biomes.BIRCH_FOREST, Biomes.SAVANNA),
		40, 1, 4);

	/** 大山雀(Parus major)—— 森林树冠活跃小鸟(M4a 验证物种,美术占位) */
	public static final BirdSpecies TIT = new BirdSpecies(
		"tit", "山雀", TitEntity.class, BirdSpecies.Habitat.FOREST,
		true, 0.25f, 0.3f,
		4.0, 0.24, 0.55, 16.0,
		7.0, 3.0, 6.0, 9.0, 16.0,
		0.0, 0.0,
		0.35F, 0.4F, 10.0, 25.0,
		false,
		ModSounds.TIT_AMBIENT, ModSounds.TIT_SCARED,
		ModSounds.TIT_HURT, ModSounds.TIT_DEATH, ModSounds.TIT_FLAP,
		"little_egret", // 美术占位:复用白鹭资源,用户出稿后改 "tit"
		List.of(Biomes.FOREST, Biomes.BIRCH_FOREST, Biomes.FLOWER_FOREST,
			Biomes.DARK_FOREST, Biomes.CHERRY_GROVE),
		30, 1, 3);

	/** 全部物种(图鉴页序 = 此列表顺序) */
	private static final List<BirdSpecies> ALL = List.of(LITTLE_EGRET, SPARROW, TIT);

	private static final Map<String, BirdSpecies> BY_ID = new LinkedHashMap<>();
	/** 实体类型 → 物种(实体实例查询) */
	private static final Map<EntityType<?>, BirdSpecies> BY_TYPE = new IdentityHashMap<>();
	/** 物种 → 实体类型(客户端渲染注册用) */
	private static final Map<BirdSpecies, EntityType<?>> TYPE_BY_SPECIES = new IdentityHashMap<>();

	static {
		for (BirdSpecies species : ALL) {
			BY_ID.put(species.id(), species);
		}
	}

	/** 由 ModEntities 在实体类型注册后调用,双向建立物种 ↔ 实体类型索引 */
	public static void indexType(EntityType<?> type, BirdSpecies species) {
		BY_TYPE.put(type, species);
		TYPE_BY_SPECIES.put(species, type);
	}

	public static List<BirdSpecies> all() {
		return ALL;
	}

	public static BirdSpecies byId(String speciesId) {
		return BY_ID.get(speciesId);
	}

	/** 物种 → 实体类型(注册后才可用;调用方按上下文推断 T) */
	@SuppressWarnings("unchecked")
	public static <T extends BirdEntity> EntityType<T> entityType(BirdSpecies species) {
		return (EntityType<T>) TYPE_BY_SPECIES.get(species);
	}

	/** 观鸟模组实体 → 物种(非观鸟模组实体返回空) */
	public static Optional<BirdSpecies> speciesOf(Entity entity) {
		return Optional.ofNullable(BY_TYPE.get(entity.getType()));
	}

	/** 实体 → 物种 id;非观鸟模组实体返回空 */
	public static Optional<String> speciesIdOf(Entity entity) {
		return speciesOf(entity).map(BirdSpecies::id);
	}

	/** 物种 id → 照片归档目录名(无该物种回退 id) */
	public static String directoryName(String speciesId) {
		BirdSpecies species = BY_ID.get(speciesId);
		return species != null ? species.directoryName() : speciesId;
	}

	private SpeciesRegistry() {
	}
}
