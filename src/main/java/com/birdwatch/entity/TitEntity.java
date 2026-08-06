package com.birdwatch.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 大山雀(Parus major)—— 森林树冠活跃小鸟(M4a 验证物种)。
 *
 * 行为:通用地面行为集(树冠间蹦跳 + 短途飞行),白天活跃;美术资源暂占位白鹭,
 * 用户 Blockbench 出稿后改 SpeciesRegistry.TIT 的 assetPrefix。
 */
public class TitEntity extends BirdEntity {
	public TitEntity(EntityType<? extends TitEntity> type, Level level) {
		super(type, level);
	}
}
