package com.birdwatch.client.entity;

import com.birdwatch.entity.LittleEgretEntity;
import com.geckolib.constant.DataTickets;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * 小白鹭 GeckoLib 渲染器(M2b)。
 * 状态经 extractRenderState 复制进渲染状态,动画处理器按状态选动画。
 * 数据票经 GeoRenderState 接口访问(运行时由 EntityRenderStateMixin 提供实现)。
 */
public class LittleEgretGeoRenderer extends GeoEntityRenderer<LittleEgretEntity, LittleEgretRenderState> {
	public LittleEgretGeoRenderer(EntityRendererProvider.Context context) {
		super(context, new LittleEgretGeoModel());
		this.shadowRadius = 0.3F;
	}

	@Override
	public LittleEgretRenderState createRenderState(LittleEgretEntity entity, Void unused) {
		LittleEgretRenderState state = new LittleEgretRenderState();
		GeoRenderState geoState = state;
		// 显式填充动画管理器数据票(AnimationProcessor 渲染时必读)
		geoState.addGeckolibData(DataTickets.ANIMATABLE_INSTANCE_ID, (long) entity.getId());
		geoState.addGeckolibData(DataTickets.ANIMATABLE_MANAGER,
			entity.getAnimatableInstanceCache().getManagerForId(entity.getId()));
		return state;
	}

	@Override
	public void extractRenderState(LittleEgretEntity egret, LittleEgretRenderState state, float partialTick) {
		// 必须先走 super:基类负责 vanilla 字段(entityType 等)与 LivingEntity 字段
		super.extractRenderState(egret, state, partialTick);
		state.behaviorState = egret.getSyncedState();
	}
}
