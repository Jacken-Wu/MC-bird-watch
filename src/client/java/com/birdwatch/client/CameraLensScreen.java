package com.birdwatch.client;

import com.birdwatch.menu.CameraLensMenuHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 相机镜头槽容器屏幕(原版储物袋式交互):
 * 1 格镜头槽 + 玩家背包,拖放镜头进出。
 *
 * 26.2 屏幕不绑定容器纹理,默认背景不可见 —— 槽位框全部自绘:
 * 半透明深色底 + 1px 边框槽位(镜头槽金色高亮)+ 标签/提示文字。
 * 槽位坐标直接取自 menu.slots(屏幕偏移 leftPos/topPos),与可交互格子严格对齐。
 */
public class CameraLensScreen extends AbstractContainerScreen<CameraLensMenuHandler> {
	private static final int SCREEN_W = 176;
	private static final int SCREEN_H = 140;
	private static final int SLOT = 18;

	private static final int COLOR_BG = 0x99000000;
	private static final int COLOR_SLOT_BORDER = 0xFF8B8B8B;
	private static final int COLOR_SLOT_INNER = 0x55000000;
	/** 金色:专用镜头槽 */
	private static final int COLOR_LENS_BORDER = 0xFFE8C97A;
	private static final int COLOR_TEXT = 0xFFAAAAAA;
	private static final int COLOR_TITLE = 0xFFFFFFFF;

	public CameraLensScreen(CameraLensMenuHandler menu, Inventory inventory, Component title) {
		// 26.2 尺寸经构造器传入(imageWidth/imageHeight 为 final)
		super(menu, inventory, title, SCREEN_W, SCREEN_H);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// 不调 super:无绑定纹理,默认背景不可见;全部自绘
		Font font = Minecraft.getInstance().font;

		// 半透明背景(储物袋风格:世界隐约可见)
		graphics.fill(this.leftPos, this.topPos, this.leftPos + SCREEN_W - 1, this.topPos + SCREEN_H - 1, COLOR_BG);

		// 标题
		graphics.text(font, this.title, this.leftPos + 8, this.topPos + 6, COLOR_TITLE);

		// 槽位:坐标取自菜单 Slot(leftPos/topPos 偏移),与可交互格子严格对齐
		Slot lensSlot = null;
		for (Slot slot : this.menu.slots) {
			int x = this.leftPos + slot.x;
			int y = this.topPos + slot.y;
			boolean lens = slot.index == 0; // 0 号格 = 镜头槽
			if (lens) {
				lensSlot = slot;
			}
			drawSlot(graphics, x, y, lens ? COLOR_LENS_BORDER : COLOR_SLOT_BORDER);
		}

		// 镜头槽标签(金色槽上方居中)
		if (lensSlot != null) {
			Component lensLabel = Component.translatable("screen.birdwatch.lens_mount.lens_label");
			graphics.text(font, lensLabel,
				this.leftPos + lensSlot.x + SLOT / 2 - font.width(lensLabel) / 2,
				this.topPos + lensSlot.y - 10, COLOR_TEXT);
		}

		// 物品栏标签(玩家物品栏第一行上方)
		graphics.text(font, Component.translatable("container.inventory"), this.leftPos + 8, this.topPos + 43, COLOR_TEXT);

		// 底部提示
		graphics.text(font, Component.translatable("screen.birdwatch.lens_mount.hint"), this.leftPos + 8,
			this.topPos + SCREEN_H - 9, COLOR_TEXT);
	}

	/** 槽位框:1px 边框 + 半透明内底 */
	private void drawSlot(GuiGraphicsExtractor g, int x, int y, int borderColor) {
		g.fill(x, y, x + SLOT - 1, y + SLOT - 1, borderColor);
		g.fill(x + 1, y + 1, x + SLOT - 2, y + SLOT - 2, COLOR_SLOT_INNER);
	}
}
