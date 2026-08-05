package com.birdwatch.client;

import com.birdwatch.BirdWatchMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 相册屏幕(M1 雏形):照片网格 + 详情页。
 * 缩略图用完整分辨率 NativeImage 注册纹理(M2 起做下采样优化)。
 */
public class AlbumScreen extends Screen {
	private static final int MAX_PHOTOS = 30;
	private final Map<String, Identifier> textureCache = new HashMap<>();
	private List<PhotoIndex.PhotoRecord> photos;
	private PhotoIndex.PhotoRecord selected;
	private Button backButton;

	public AlbumScreen() {
		super(Component.translatable("screen.birdwatch.album"));
	}

	@Override
	protected void init() {
		super.init();
		photos = PhotoIndex.list();
		if (photos.size() > MAX_PHOTOS) {
			photos = photos.subList(0, MAX_PHOTOS);
		}
		selected = null;
		backButton = Button.builder(Component.translatable("gui.birdwatch.back"), b -> {
			selected = null;
			init();
		}).bounds(this.width / 2 - 50, this.height - 28, 100, 20).build();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		Minecraft minecraft = Minecraft.getInstance();

		if (selected == null) {
			graphics.text(minecraft.font, this.getTitle(), this.width / 2 - 40, 8, 0xFFFFFFFF);
			if (photos.isEmpty()) {
				graphics.text(minecraft.font, Component.translatable("screen.birdwatch.album.empty"),
					this.width / 2 - 60, this.height / 2, 0xFF888888);
				return;
			}
			int cols = 5;
			int thumb = 100;
			int gap = 12;
			int startX = this.width / 2 - (cols * thumb + (cols - 1) * gap) / 2;
			int startY = 40;
			for (int i = 0; i < photos.size(); i++) {
				PhotoIndex.PhotoRecord record = photos.get(i);
				int x = startX + (i % cols) * (thumb + gap);
				int y = startY + (i / cols) * (thumb + gap);
				graphics.blit(textureFor(record), x, y, 0, 0, thumb, thumb, thumb, thumb);
			}
			// 已选照片高亮
			if (hoveredPhoto != null) {
				int x = startX + (hoveredIndex % cols) * (thumb + gap);
				int y = startY + (hoveredIndex / cols) * (thumb + gap);
				graphics.outline(x - 2, y - 2, x + thumb + 2, y + thumb + 2, 0xFFFFFFFF);
			}
			graphics.text(minecraft.font, Component.translatable("screen.birdwatch.album.count", photos.size()),
				this.width / 2 - 40, this.height - 28, 0xFF888888);
		} else {
			drawDetail(graphics, minecraft);
		}
	}

	private void drawDetail(GuiGraphicsExtractor graphics, Minecraft minecraft) {
		PhotoIndex.PhotoRecord record = selected;
		int maxW = this.width - 60;
		int maxH = this.height - 90;
		// 按比例缩放显示
		int imgW = maxW;
		int imgH = maxW * 9 / 16;
		if (imgH > maxH) {
			imgH = maxH;
			imgW = maxH * 16 / 9;
		}
		int x = this.width / 2 - imgW / 2;
		int y = 34;
		graphics.blit(textureFor(record), x, y, 0, 0, imgW, imgH, imgW, imgH);

		graphics.text(minecraft.font, Component.literal("f/" + record.data().getOrDefault("aperture", "?")
			+ "  1/" + record.data().getOrDefault("shutter", "?")), x, y + imgH + 6, 0xFFCCCCCC);
		backButton.setX(this.width / 2 - 50);
		backButton.setY(this.height - 28);
		backButton.visible = true;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
		if (super.mouseClicked(event, bl)) {
			return true;
		}
		if (event.button() != 0 || selected != null) {
			return false;
		}
		double mouseX = event.x();
		double mouseY = event.y();
		if (photos != null && !photos.isEmpty()) {
			int cols = 5;
			int thumb = 100;
			int gap = 12;
			int startX = this.width / 2 - (cols * thumb + (cols - 1) * gap) / 2;
			int startY = 40;
			for (int i = 0; i < photos.size(); i++) {
				int x = startX + (i % cols) * (thumb + gap);
				int y = startY + (i / cols) * (thumb + gap);
				if (mouseX >= x && mouseX < x + thumb && mouseY >= y && mouseY < y + thumb) {
					selected = photos.get(i);
					init();
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		super.mouseMoved(mouseX, mouseY);
		hoveredPhoto = null;
		if (selected == null && photos != null && !photos.isEmpty()) {
			int cols = 5;
			int thumb = 100;
			int gap = 12;
			int startX = this.width / 2 - (cols * thumb + (cols - 1) * gap) / 2;
			int startY = 40;
			for (int i = 0; i < photos.size(); i++) {
				int x = startX + (i % cols) * (thumb + gap);
				int y = startY + (i / cols) * (thumb + gap);
				if (mouseX >= x && mouseX < x + thumb && mouseY >= y && mouseY < y + thumb) {
					hoveredPhoto = photos.get(i);
					hoveredIndex = i;
					break;
				}
			}
		}
	}

	private Identifier textureFor(PhotoIndex.PhotoRecord record) {
		Identifier cached = textureCache.get(record.name());
		if (cached != null) {
			return cached;
		}
		Identifier id = Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "photo_" + record.name().hashCode());
		try (InputStream in = Files.newInputStream(record.pngPath())) {
			NativeImage image = NativeImage.read(in);
			Minecraft.getInstance().getTextureManager().register(id,
				new DynamicTexture(() -> "birdwatch photo " + record.name(), image));
			textureCache.put(record.name(), id);
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 无法加载照片纹理 {}", record.pngPath(), e);
		}
		return id;
	}

	private PhotoIndex.PhotoRecord hoveredPhoto;
	private int hoveredIndex = -1;
}
