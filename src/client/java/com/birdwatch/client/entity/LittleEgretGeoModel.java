package com.birdwatch.client.entity;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.entity.LittleEgretEntity;
import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;
import net.minecraft.resources.Identifier;

/**
 * 小白鹭 GeckoLib 模型(M2b 用户 Blockbench 建模,骨骼见 little_egret.geo.json)。
 * GeckoLib 5 资源约定:文件放 assets/<mod>/geckolib/{models,animations}/,
 * 返回的 Identifier 用剥前缀后的规范化键(<mod>:<name>)。
 */
public class LittleEgretGeoModel extends GeoModel<LittleEgretEntity> {
	@Override
	public Identifier getModelResource(GeoRenderState state) {
		return Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "little_egret");
	}

	@Override
	public Identifier getTextureResource(GeoRenderState state) {
		return Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "textures/entity/little_egret.png");
	}

	@Override
	public Identifier getAnimationResource(LittleEgretEntity animatable) {
		return Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "little_egret");
	}
}
