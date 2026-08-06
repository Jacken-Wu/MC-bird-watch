package com.birdwatch.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 小白鹭(Egretta garzetta)—— 湿地涉禽,定调物种(M4a 后为薄物种实例)。
 *
 * 全部行为由 {@link BirdEntity} 基类提供,差异参数见 SpeciesRegistry.LITTLE_EGRET
 * (湿地行为集:寻水/回水/水边觅食/水边闲逛)。
 */
public class LittleEgretEntity extends BirdEntity {
	public LittleEgretEntity(EntityType<? extends LittleEgretEntity> type, Level level) {
		super(type, level);
	}
}
