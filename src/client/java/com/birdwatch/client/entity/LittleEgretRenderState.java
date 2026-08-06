package com.birdwatch.client.entity;

import com.birdwatch.entity.LittleEgretEntity;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/**
 * 小白鹭渲染状态(26.2 渲染状态分离体系)。
 *
 * 注意:不声明 GeoRenderState 接口、不覆写 getDataMap() —— GeckoLib 的
 * EntityRenderStateMixin 已把 GeoRenderState 注入原版 EntityRenderState
 * (自带 geckolib$data 映射);自行覆写 getDataMap 会与 mixin 的读写通道
 * 分裂,导致数据票永远读不到(踩坑记录)。
 * 数据票访问统一走 ((GeoRenderState) state) 显式转换。
 */
public class LittleEgretRenderState extends LivingEntityRenderState {
	/** 行为状态(经 SynchedEntityData 同步,客户端动画驱动源) */
	public LittleEgretEntity.State behaviorState = LittleEgretEntity.State.IDLE;
}
