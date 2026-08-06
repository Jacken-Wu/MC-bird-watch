package com.birdwatch.registry;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.entity.LittleEgretEntity;
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
import net.minecraft.world.level.biome.Biomes;

/**
 * 实体注册入口(M2b:小白鹭,ID 与物种命名统一 little_egret)。
 *
 * 26.2 官方映射:EntityType.Builder.build() 需要 ResourceKey(与 Item setId 同理)。
 */
public final class ModEntities {
	/** 小白鹭 —— 湿地涉禽,河边浅滩觅食 */
	public static final EntityType<LittleEgretEntity> LITTLE_EGRET = EntityType.Builder.of(LittleEgretEntity::new, MobCategory.CREATURE)
		.sized(0.5f, 0.9f)
		.clientTrackingRange(10)
		.build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "little_egret")));

	public static void registerAll() {
		Registry.register(BuiltInRegistries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "little_egret"), LITTLE_EGRET);

		FabricDefaultAttributeRegistry.register(LITTLE_EGRET, createLittleEgretAttributes());
		// 自检:属性注册必须生效,否则 summon 时 Mob.finalizeSpawn 会 NPE
		if (!net.minecraft.world.entity.ai.attributes.DefaultAttributes.hasSupplier(LITTLE_EGRET)) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 小白鹭属性注册失败:DefaultAttributes 无 LITTLE_EGRET 条目!");
		} else {
			BirdWatchMod.LOGGER.info("[BirdWatch] 小白鹭属性注册自检通过");
		}
		registerSpawns();
	}

	/**
	 * 以原版活体实体标准属性集为基础(26 个属性,含 26.2 的 waypoint/step_height 等),
	 * 再覆盖小白鹭个性化值 —— 属性集与版本自动同步,避免手抄遗漏导致
	 * "Can't find attribute xxx" 崩溃(waypoint_transmit_range / step_height 踩坑记录)。
	 */
	private static AttributeSupplier createLittleEgretAttributes() {
		return LivingEntity.createLivingAttributes()
			.add(Attributes.MAX_HEALTH, 12.0)
			.add(Attributes.MOVEMENT_SPEED, 0.25)
			.add(Attributes.FOLLOW_RANGE, 24.0)
			.add(Attributes.FLYING_SPEED, 0.4)
			.build();
	}

	/** 自然刷新:湿地/河流/海滩浅滩(白鹭刷河边浅滩) */
	private static void registerSpawns() {
		BiomeModifications.addSpawn(
			BiomeSelectors.includeByKey(Biomes.SWAMP, Biomes.MANGROVE_SWAMP, Biomes.RIVER, Biomes.BEACH),
			MobCategory.CREATURE, LITTLE_EGRET, 12, 1, 3);
	}

	private ModEntities() {
	}
}
