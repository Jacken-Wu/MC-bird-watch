package com.birdwatch.client.photo;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.special.SpecialModelRenderer;

/**
 * 印刷照片特殊模型类型(birdwatch:photo_print):
 * 供物品模型 "type": "minecraft:special" 引用,渲染动态照片纹理。
 * 注册:反射往 SpecialModelRenderers.ID_MAPPER put(原版字段私有,无公开注册 API)。
 */
public final class PhotoPrintSpecialModel {
	public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

	public record Unbaked() implements SpecialModelRenderer.Unbaked<PhotoPrintSpecialRenderer.PhotoPrintData> {
		@Override
		public SpecialModelRenderer<PhotoPrintSpecialRenderer.PhotoPrintData> bake(
			SpecialModelRenderer.BakingContext context) {
			return new PhotoPrintSpecialRenderer();
		}

		@Override
		public MapCodec<? extends SpecialModelRenderer.Unbaked<PhotoPrintSpecialRenderer.PhotoPrintData>> type() {
			return MAP_CODEC;
		}
	}

	private PhotoPrintSpecialModel() {
	}
}
