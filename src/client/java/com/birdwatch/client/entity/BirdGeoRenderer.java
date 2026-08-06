package com.birdwatch.client.entity;

import com.birdwatch.bird.BirdSpecies;
import com.birdwatch.entity.BirdEntity;
import com.geckolib.constant.DataTickets;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * 鸟 GeckoLib 渲染器(M4a 泛化:全部物种共用一个渲染器,模型按物种参数取资源)。
 * 状态经 extractRenderState 复制进渲染状态,动画处理器按状态选动画。
 * 数据票经 GeoRenderState 接口访问(运行时由 EntityRenderStateMixin 提供实现)。
 */
public class BirdGeoRenderer extends GeoEntityRenderer<BirdEntity, BirdRenderState> {
	public BirdGeoRenderer(EntityRendererProvider.Context context, BirdSpecies species) {
		super(context, new BirdGeoModel(species));
		this.shadowRadius = 0.3F;
	}

	@Override
	public BirdRenderState createRenderState(BirdEntity entity, Void unused) {
		BirdRenderState state = new BirdRenderState();
		GeoRenderState geoState = state;
		// 显式填充动画管理器数据票(AnimationProcessor 渲染时必读)
		geoState.addGeckolibData(DataTickets.ANIMATABLE_INSTANCE_ID, (long) entity.getId());
		geoState.addGeckolibData(DataTickets.ANIMATABLE_MANAGER,
			entity.getAnimatableInstanceCache().getManagerForId(entity.getId()));
		return state;
	}

	@Override
	public void extractRenderState(BirdEntity bird, BirdRenderState state, float partialTick) {
		// 必须先走 super:基类负责 vanilla 字段(entityType 等)与 LivingEntity 字段
		super.extractRenderState(bird, state, partialTick);
		state.behaviorState = bird.getSyncedState();
	}
}
