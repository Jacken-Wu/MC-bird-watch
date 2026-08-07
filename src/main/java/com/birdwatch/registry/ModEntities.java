package com.birdwatch.registry;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.bird.BirdSpecies;
import com.birdwatch.bird.SpeciesRegistry;
import com.birdwatch.entity.BirdEntity;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

import java.lang.reflect.Constructor;

/**
 * 实体注册入口(M4a:数据驱动重构 —— 遍历 SpeciesRegistry 统一注册)。
 *
 * 每个物种注册四件事:EntityType(反射工厂,构造器约定 (EntityType, Level))、
 * 属性(26.2 必须以 LivingEntity.createLivingAttributes() 为基础)、
 * 物种索引(SpeciesRegistry.indexType)、自然刷新(群系/权重/组大小)。
 *
 * 26.2 官方映射:EntityType.Builder.build() 需要 ResourceKey(与 Item setId 同理)。
 */
public final class ModEntities {
	public static void registerAll() {
		for (BirdSpecies species : SpeciesRegistry.all()) {
			registerSpecies(species);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends BirdEntity> void registerSpecies(BirdSpecies species) {
		Class<T> clazz = (Class<T>) species.entityClass();
		Identifier id = Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, species.id());
		// 反射工厂:物种实体类构造器统一为 (EntityType, Level)
		// 类别用 AMBIENT(独立容量池):CREATURE 与鸡猪牛羊共享每区块 10 只上限,
		// 动物多时鸟无名额刷不出(实测踩坑);AMBIENT 池只装鸟,蝙蝠稀疏只因权重低
		EntityType<T> type = EntityType.Builder.of((EntityType.EntityFactory<T>) (entityType, level) -> {
			try {
				Constructor<T> ctor = clazz.getDeclaredConstructor(EntityType.class, Level.class);
				return ctor.newInstance(entityType, level);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException("无法创建实体 " + species.id(), e);
			}
		}, MobCategory.AMBIENT)
			.sized(species.width(), species.height())
			.clientTrackingRange(10)
			.build(ResourceKey.create(Registries.ENTITY_TYPE, id));

		Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type);
		SpeciesRegistry.indexType(type, species);
		FabricDefaultAttributeRegistry.register(type, createAttributes(species));
		// 自检:属性注册必须生效,否则 summon 时 Mob.finalizeSpawn 会 NPE
		if (!net.minecraft.world.entity.ai.attributes.DefaultAttributes.hasSupplier(type)) {
			BirdWatchMod.LOGGER.error("[BirdWatch] {} 属性注册失败:DefaultAttributes 无对应条目!", species.id());
		} else {
			BirdWatchMod.LOGGER.debug("[BirdWatch] {} 注册完成(属性自检通过)", species.id());
		}
		registerSpawns(species, type);
	}

	/**
	 * 以原版活体实体标准属性集为基础(26 个属性,含 26.2 的 waypoint/step_height 等),
	 * 再覆盖物种个性化值 —— 属性集与版本自动同步,避免手抄遗漏导致
	 * "Can't find attribute xxx" 崩溃(waypoint_transmit_range / step_height 踩坑记录)。
	 */
	private static AttributeSupplier createAttributes(BirdSpecies species) {
		return LivingEntity.createLivingAttributes()
			.add(Attributes.MAX_HEALTH, species.maxHealth())
			.add(Attributes.MOVEMENT_SPEED, species.movementSpeed())
			.add(Attributes.FOLLOW_RANGE, species.followRange())
			.add(Attributes.FLYING_SPEED, species.flyingSpeed())
			.build();
	}

	/** 自然刷新:群系 + 权重 + 组大小(权重越高越常见;数据见物种记录;类别 AMBIENT 独立容量) */
	private static <T extends BirdEntity> void registerSpawns(BirdSpecies species, EntityType<T> type) {
		BiomeModifications.addSpawn(
			BiomeSelectors.includeByKey(species.spawnBiomes()),
			MobCategory.AMBIENT, type,
			species.spawnWeight(), species.spawnMinGroup(), species.spawnMaxGroup());
	}

	private ModEntities() {
	}
}
