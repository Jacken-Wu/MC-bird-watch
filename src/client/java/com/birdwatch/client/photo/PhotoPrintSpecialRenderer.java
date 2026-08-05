package com.birdwatch.client.photo;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.config.BirdWatchConfig;
import com.birdwatch.item.PhotoPrintItem;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 印刷照片特殊渲染器(M2a):
 * 把照片(按裁剪矩形裁切)注册为动态纹理,渲染成带白色卡边的平面 ——
 * 背包图标/手持/展示框全部显示照片内容。
 */
public class PhotoPrintSpecialRenderer implements SpecialModelRenderer<PhotoPrintSpecialRenderer.PhotoPrintData> {

	/** 渲染参数:照片路径(相对 photos 根)+ 裁剪矩形(归一化) */
	public record PhotoPrintData(String photoPath, double cropX, double cropY, double cropW, double cropH) {
		/** 纹理缓存 key(照片 + 裁剪矩形) */
		String cacheKey() {
			return photoPath + "#" + cropX + "," + cropY + "," + cropW + "," + cropH;
		}
	}

	@Override
	public PhotoPrintData extractArgument(ItemStack stack) {
		var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		if (data == null) {
			return new PhotoPrintData("", 0, 0, 1, 1);
		}
		var tag = data.copyTag();
		String photo = tag.getString(PhotoPrintItem.KEY_PHOTO).orElse("");
		double[] crop = parseCrop(tag.getString(PhotoPrintItem.KEY_CROP).orElse(""));
		return new PhotoPrintData(photo, crop[0], crop[1], crop[2], crop[3]);
	}

	private static double[] parseCrop(String crop) {
		if (crop != null && !crop.isBlank()) {
			String[] parts = crop.split(",");
			if (parts.length == 4) {
				try {
					return new double[]{
						Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
						Double.parseDouble(parts[2]), Double.parseDouble(parts[3])
					};
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return new double[]{0, 0, 1, 1};
	}

	@Override
	public void getExtents(Consumer<Vector3fc> consumer) {
		// 注意:extents 的 z 必须为 0 —— 非零 z 会导致 GUI 物品图标渲染异常(透明)。
		// GUI 正交投影下几何 z 无视觉影响,展示框贴面(z 前移)另寻方案。
		consumer.accept(new Vector3f(-0.5f, -0.5f, 0.0f));
		consumer.accept(new Vector3f(0.5f, 0.5f, 0.0f));
	}

	/** 纹理缓存:photoPath+crop → 已注册的裁剪纹理 ID(首次渲染时注册,后续复用) */
	private static final java.util.Map<String, Identifier> TEXTURE_CACHE = new java.util.HashMap<>();

	@Override
	public void submit(PhotoPrintData data, PoseStack poseStack, SubmitNodeCollector collector,
		int light, int overlay, boolean hasGlint, int tint) {
		if (data.photoPath().isEmpty()) {
			return;
		}
		try {
			Identifier texId = TEXTURE_CACHE.computeIfAbsent(data.cacheKey(),
				k -> loadCropTexture(data.photoPath(), data.cropX(), data.cropY(), data.cropW(), data.cropH()));
			if (texId == null) {
				return;
			}
			NativeImage img = Minecraft.getInstance().getTextureManager().getTexture(texId)
				instanceof DynamicTexture dt ? dt.getPixels() : null;
			if (img == null) {
				return;
			}
			int w = img.getWidth();
			int h = img.getHeight();

			// 薄片模型(深度 1 双面,避免展示框中从背面被剔除)。
			// 方向修正见 loadCropTexture:注册纹理时垂直翻转像素
			// (UV v0 位于模型底部面,预翻转使显示方向正确,不动几何/变换)。
			var mesh = new net.minecraft.client.model.geom.builders.MeshDefinition();
			mesh.getRoot().addOrReplaceChild("photo",
				net.minecraft.client.model.geom.builders.CubeListBuilder.create()
					.texOffs(0, 0)
					.addBox("photo", 0.0F, 0.0F, -0.5F, w, h, 1, w, h),
				net.minecraft.client.model.geom.PartPose.ZERO);
			ModelPart part = mesh.getRoot().bake(w, h);
			poseStack.pushPose();
			// z 前移 0.5:平面中心贴到展示框前表面(如地图贴框)。
			// 只动 poseStack,不碰 extents/GUI 参数(正交投影下 z 平移无视觉影响)
			poseStack.translate(0.0F, 0.0F, 0.5F);
			poseStack.scale(16.0f / w, 16.0f / h, 1.0f);
			collector.submitModel(new PhotoQuadModel(part), data, poseStack,
				RenderTypes.entityCutout(texId), light, overlay, tint, null);
			poseStack.popPose();
		} catch (Exception e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 印刷照片渲染失败 {}", data.photoPath(), e);
		}
	}

	/** 加载照片 → 按裁剪矩形裁切 → 注册动态纹理;失败返回 null */
	private static Identifier loadCropTexture(String photoPath, double cropX, double cropY, double cropW, double cropH) {
		try {
			Path png = BirdWatchConfig.photosRoot().resolve(photoPath);
			if (!Files.exists(png)) {
				return null;
			}
			NativeImage source;
			try (InputStream in = Files.newInputStream(png)) {
				source = NativeImage.read(in);
			}
			int w = Math.max(1, (int) Math.round(source.getWidth() * cropW));
			int h = Math.max(1, (int) Math.round(source.getHeight() * cropH));
			int sx = (int) Math.round(source.getWidth() * cropX);
			int sy = (int) Math.round(source.getHeight() * cropY);
			NativeImage cropped = new NativeImage(w, h, true);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					cropped.setPixel(x, y, source.getPixel(Math.min(source.getWidth() - 1, sx + x),
						Math.min(source.getHeight() - 1, sy + y)));
				}
			}
			source.close();
			// 像素 180° 旋转(水平+垂直翻转):物品模型 UV v0 在底部面且 GUI 渲染
			// 为镜像投影 —— 预旋转像素使物品栏/展示框显示方向正确
			NativeImage flipped = new NativeImage(w, h, true);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					flipped.setPixel(x, y, cropped.getPixel(w - 1 - x, h - 1 - y));
				}
			}
			cropped.close();
			Identifier texId = Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID,
				"print_" + Integer.toHexString(photoPath.hashCode()) + "_" + Integer.toHexString(w * 31 + h));
			Minecraft.getInstance().getTextureManager().register(texId,
				new DynamicTexture(() -> "birdwatch photo print " + photoPath, flipped));
			return texId;
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 印刷照片纹理加载失败 {}", photoPath, e);
			return null;
		}
	}

	/** 单平面模型(无动画) */
	private static final class PhotoQuadModel extends Model<PhotoPrintData> {
		private PhotoQuadModel(ModelPart root) {
			super(root, id -> RenderTypes.entityCutout(id));
		}

		@Override
		public void setupAnim(PhotoPrintData state) {
		}
	}
}
