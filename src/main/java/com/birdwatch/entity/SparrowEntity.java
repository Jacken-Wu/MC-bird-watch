package com.birdwatch.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 树麻雀(Passer montanus)—— 村落/聚落常见小鸟(M4a 验证物种)。
 *
 * 行为:通用地面行为集(随机蹦跳 + 短途飞行),白天活跃;美术资源暂占位白鹭,
 * 用户 Blockbench 出稿后改 SpeciesRegistry.SPARROW 的 assetPrefix。
 */
public class SparrowEntity extends BirdEntity {
	public SparrowEntity(EntityType<? extends SparrowEntity> type, Level level) {
		super(type, level);
	}
}
