package com.birdwatch.client.entity;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.entity.HeronEntity;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.Identifier;

/**
 * 白鹭渲染器(M2a 静态模型;M2b 替换动画/贴图)。
 */
public class HeronRenderer extends MobRenderer<HeronEntity, HeronRenderState, HeronModel> {
	public static final ModelLayerLocation HERON_LAYER = new ModelLayerLocation(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "heron"), "main");

	/** 模型层定义提供者(供 ModelLayerRegistry 注册) */
	public static final ModelLayerRegistry.TexturedLayerDefinitionProvider HERON_LAYER_PROVIDER =
		HeronModel::createBodyLayer;

	public HeronRenderer(EntityRendererProvider.Context context) {
		super(context, new HeronModel(context.bakeLayer(HERON_LAYER)), 0.3F);
	}

	@Override
	public HeronRenderState createRenderState() {
		return new HeronRenderState();
	}

	@Override
	public void extractRenderState(HeronEntity heron, HeronRenderState state, float partialTick) {
		super.extractRenderState(heron, state, partialTick);
	}

	@Override
	public Identifier getTextureLocation(HeronRenderState state) {
		return Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "textures/entity/heron.png");
	}
}
