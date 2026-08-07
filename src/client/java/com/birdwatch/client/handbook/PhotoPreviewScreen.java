package com.birdwatch.client.handbook;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.config.BirdWatchConfig;
import com.birdwatch.item.PhotoPrintItem;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 印刷照片预览(M2a):右键印刷照片打开,展示裁剪后的照片大图 + 参数信息。
 */
public class PhotoPreviewScreen extends Screen {
	private final ItemStack stack;
	private Identifier textureId;
	private int texW;
	private int texH;
	private Button closeButton;

	public PhotoPreviewScreen(ItemStack stack) {
		super(Component.translatable("screen.birdwatch.photo_preview"));
		this.stack = stack;
		loadTexture();
	}

	/**
	 * 按裁剪矩形裁切照片并注册纹理。
	 * 新架构:photo = printId,读客户端 print_cache(缺失时请求服务端);
	 * 旧架构兼容:photo 为路径(含 "/" 或 ".png"),走旧照片根解析。
	 */
	private void loadTexture() {
		var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		if (data == null) {
			return;
		}
		var tag = data.copyTag();
		String photo = tag.getString(PhotoPrintItem.KEY_PHOTO).orElse("");
		if (photo.isEmpty()) {
			return;
		}
		double[] crop = parseCrop(tag.getString(PhotoPrintItem.KEY_CROP).orElse(""));
		Path png;
		if (photo.contains("/") || photo.contains(".png")) {
			// 旧架构:路径形式,读旧照片根
			png = com.birdwatch.client.photo.PhotoStorage.resolvePhoto(photo);
		} else {
			// 新架构:printId,读客户端缓存;缺失时按需请求服务端
			png = com.birdwatch.client.photo.PhotoStorage.printCacheRoot().resolve(photo + ".png");
			if (!Files.exists(png)) {
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new com.birdwatch.network.ModNetworking.PrintImageRequestPayload(photo));
				return;
			}
		}
		try (InputStream in = Files.newInputStream(png)) {
			NativeImage source = NativeImage.read(in);
			int w = Math.max(1, (int) Math.round(source.getWidth() * crop[2]));
			int h = Math.max(1, (int) Math.round(source.getHeight() * crop[3]));
			int sx = (int) Math.round(source.getWidth() * crop[0]);
			int sy = (int) Math.round(source.getHeight() * crop[1]);
			NativeImage cropped = new NativeImage(w, h, true);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					cropped.setPixel(x, y, source.getPixel(Math.min(source.getWidth() - 1, sx + x),
						Math.min(source.getHeight() - 1, sy + y)));
				}
			}
			source.close();
			texW = w;
			texH = h;
			textureId = Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID,
				"preview_" + Integer.toHexString(photo.hashCode()) + "_" + w + "x" + h);
			Minecraft.getInstance().getTextureManager().register(textureId,
				new DynamicTexture(() -> "birdwatch preview " + photo, cropped));
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 印刷照片预览加载失败 {}", png, e);
		}
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
	protected void init() {
		super.init();
		clearWidgets();
		closeButton = Button.builder(Component.translatable("gui.birdwatch.close"), b -> this.onClose())
			.bounds(this.width / 2 - 50, this.height - 28, 100, 20).build();
		closeButton.visible = true;
		this.addRenderableWidget(closeButton);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		// 背景暗化
		graphics.fill(0, 0, this.width, this.height, 0x99000000);
		Component title = this.getTitle();
		graphics.text(mc.font, title, this.width / 2 - mc.font.width(title) / 2, 8, 0xFFFFFFFF);

		if (textureId == null) {
			graphics.text(mc.font, Component.translatable("screen.birdwatch.photo_preview.invalid"),
				this.width / 2 - 60, this.height / 2, 0xFF888888);
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			return;
		}
		// 大图按比例显示
		int maxW = this.width - 80;
		int maxH = this.height - 80;
		double ratio = Math.min((double) maxW / texW, (double) maxH / texH);
		int w = (int) (texW * ratio);
		int h = (int) (texH * ratio);
		int x = this.width / 2 - w / 2;
		int y = 34;
		graphics.blit(textureId, x, y, x + w, y + h, 0.0f, 1.0f, 0.0f, 1.0f);

		// 参数信息(物种 + 评分)
		var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		if (data != null) {
			var tag = data.copyTag();
			String species = tag.getString(PhotoPrintItem.KEY_SPECIES).orElse("");
			int score = tag.getInt(PhotoPrintItem.KEY_SCORE).orElse(0);
			String info = "";
			if (!species.isEmpty()) {
				info = Component.translatable("handbook.birdwatch." + species + ".name").getString() + "  ";
			}
			info += Component.translatable("item.birdwatch.photo_print.tooltip.score", score).getString();
			graphics.text(mc.font, Component.literal(info), x, y + h + 8, 0xFFCCCCCC);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
