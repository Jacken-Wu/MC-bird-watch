package com.birdwatch.client.entity;

import com.birdwatch.bird.BirdSpecies;
import com.birdwatch.entity.BirdEntity;
import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;
import net.minecraft.resources.Identifier;

/**
 * 鸟 GeckoLib 模型(M4a 泛化:按物种参数取资源)。
 * GeckoLib 5 资源约定:文件放 assets/<mod>/geckolib/{models,animations}/,
 * 返回的 Identifier 用剥前缀后的规范化键(<mod>:<前缀>)。
 * 资源前缀见 BirdSpecies.assetPrefix(美术未出稿时物种间可共享占位资源)。
 */
public class BirdGeoModel extends GeoModel<BirdEntity> {
	private final BirdSpecies species;

	public BirdGeoModel(BirdSpecies species) {
		this.species = species;
	}

	@Override
	public Identifier getModelResource(GeoRenderState state) {
		return species.modelId();
	}

	@Override
	public Identifier getTextureResource(GeoRenderState state) {
		return species.textureId();
	}

	@Override
	public Identifier getAnimationResource(BirdEntity animatable) {
		return species.modelId();
	}
}
