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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 印刷照片选择(M2a):背包中有多张可贴入的印刷照片时,
 * 列出候选(缩略图 + 评分),点击选择贴入图鉴。
 */
public class PhotoSelectScreen extends Screen {
	private static final int ROW_H = 56;
	private static final int THUMB_W = 80;
	private static final int THUMB_H = 44;

	private final List<ItemStack> candidates;
	private final String speciesId;
	private final Screen returnScreen;
	private final Map<String, Identifier> textureCache = new HashMap<>();
	private Button cancelButton;
	private int hovered = -1;
	/** 滚动偏移(照片多时列表超出可视区) */
	private int scroll;

	public PhotoSelectScreen(List<ItemStack> candidates, String speciesId, Screen returnScreen) {
		super(Component.translatable("screen.birdwatch.photo_select"));
		this.candidates = candidates;
		this.speciesId = speciesId;
		this.returnScreen = returnScreen;
	}

	@Override
	protected void init() {
		super.init();
		clearWidgets();
		cancelButton = Button.builder(Component.translatable("gui.birdwatch.back"), b -> this.onClose())
			.bounds(this.width / 2 - 50, this.height - 28, 100, 20).build();
		cancelButton.visible = true;
		this.addRenderableWidget(cancelButton);
	}

	@Override
	public void onClose() {
		if (returnScreen != null) {
			Minecraft.getInstance().setScreenAndShow(returnScreen);
			return;
		}
		super.onClose();
	}

	/** 列表区域(居中,每行 ROW_H) */
	private int listTop() {
		return 40;
	}

	/** 列表可视区底部(取消按钮上方) */
	private int viewportBottom() {
		return this.height - 34;
	}

	/** 列表总高度 */
	private int totalHeight() {
		return candidates.size() * (ROW_H + 4);
	}

	/** 最大滚动偏移 */
	private int maxScroll() {
		return Math.max(0, totalHeight() - (viewportBottom() - listTop()));
	}

	private int clampScroll(int s) {
		return Math.max(0, Math.min(s, maxScroll()));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
			return true;
		}
		scroll = clampScroll(scroll - (int) Math.round(scrollY) * (ROW_H + 4));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		graphics.fill(0, 0, this.width, this.height, 0x99000000);
		Component title = this.getTitle();
		graphics.text(mc.font, title, this.width / 2 - mc.font.width(title) / 2, 8, 0xFFFFFFFF);

		int colW = Math.min(380, this.width - 80);
		int left = this.width / 2 - colW / 2;
		hovered = -1;
		for (int i = 0; i < candidates.size(); i++) {
			int y = listTop() + i * (ROW_H + 4) - scroll;
			if (y + ROW_H < listTop() || y > viewportBottom()) {
				continue; // 视口外,跳过渲染与命中
			}
			boolean over = mouseX >= left && mouseX < left + colW && mouseY >= y && mouseY < y + ROW_H;
			if (over) {
				hovered = i;
			}
			// 行背景 + 高亮
			graphics.fill(left, y, left + colW, y + ROW_H, over ? 0x88336600 : 0x55000000);
			// 缩略图
			Identifier tex = thumbnailFor(candidates.get(i));
			if (tex != null) {
				graphics.blit(tex, left + 6, y + (ROW_H - THUMB_H) / 2,
					left + 6 + THUMB_W, y + (ROW_H - THUMB_H) / 2 + THUMB_H, 0.0f, 1.0f, 0.0f, 1.0f);
			}
			// 文本:评分 + 档位
			var data = candidates.get(i).get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
			int score = 0;
			String tier = "";
			if (data != null) {
				var tag = data.copyTag();
				score = tag.getInt(PhotoPrintItem.KEY_SCORE).orElse(0);
				tier = tag.getString(PhotoPrintItem.KEY_TIER).orElse("");
			}
			String tierText = tier.isEmpty() ? "" : "  " + Component.translatable("item.birdwatch.photo_print.tooltip.tier." + tier).getString();
			graphics.text(mc.font, Component.translatable("item.birdwatch.photo_print.tooltip.score", score)
				.copy().append(Component.literal(tierText)),
				left + 6 + THUMB_W + 10, y + ROW_H / 2 - 4, 0xFFCCCCCC);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
		if (super.mouseClicked(event, bl)) {
			return true;
		}
		if (event.button() != 0 || hovered < 0 || hovered >= candidates.size()) {
			return false;
		}
		HandbookScreen.applyPrint(candidates.get(hovered), speciesId, returnScreen);
		this.onClose();
		return true;
	}

	/** 读印刷照片的裁剪文件,注册缩略图纹理 */
	private Identifier thumbnailFor(ItemStack stack) {
		var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		String photo = data.copyTag().getString(PhotoPrintItem.KEY_PHOTO).orElse("");
		if (photo.isEmpty()) {
			return null;
		}
		Identifier cached = textureCache.get(photo);
		if (cached != null) {
			return cached;
		}
		Path png = com.birdwatch.client.photo.PhotoStorage.resolvePhoto(photo);
		if (!Files.exists(png)) {
			return null;
		}
		try (InputStream in = Files.newInputStream(png)) {
			NativeImage image = NativeImage.read(in);
			Identifier id = Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "sel_" + photo.hashCode());
			Minecraft.getInstance().getTextureManager().register(id,
				new DynamicTexture(() -> "birdwatch select " + photo, image));
			textureCache.put(photo, id);
			return id;
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 选择界面缩略图加载失败 {}", png, e);
		}
		return null;
	}
}
