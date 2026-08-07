package com.birdwatch.client.handbook;

import com.birdwatch.bird.BestiaryRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;

import java.util.List;

/**
 * 生物图鉴界面(M4b):原版常见生物清单,拍照解锁。
 * 列表页:生物名 + 解锁前 "???" / 解锁后全名;
 * 详情页:名称 + 解锁状态 + 习性文案(每生物 1-2 行,lang 驱动)。
 * 解锁状态来自服务端附件(C2S 查询 → S2C 回传),打开时请求。
 */
public class BestiaryScreen extends Screen {
	private static final int COLS = 4;
	private static final int ROW_H = 30;
	private static final int PAGE_SIZE = 24;
	private static final int MARGIN = 30;

	private int page;
	private String selected;
	private Button closeButton;
	private Button prevButton;
	private Button nextButton;
	private Button backButton;

	public BestiaryScreen() {
		super(Component.translatable("screen.birdwatch.bestiary"));
		BestiaryProgress.request();
	}

	@Override
	protected void init() {
		super.init();
		clearWidgets();
		List<String> ids = BestiaryRegistry.allIds();
		int pages = Math.max(1, (ids.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		page = Math.max(0, Math.min(page, pages - 1));
		closeButton = Button.builder(Component.translatable("gui.birdwatch.close"), b -> this.onClose())
			.bounds(this.width / 2 - 50, this.height - 28, 100, 20).build();
		prevButton = Button.builder(Component.translatable("gui.birdwatch.prev_page"), b -> {
			page = Math.max(0, page - 1);
			init();
		}).bounds(this.width / 2 - 130, this.height - 28, 60, 20).build();
		nextButton = Button.builder(Component.translatable("gui.birdwatch.next_page"), b -> {
			page = Math.min(pages - 1, page + 1);
			init();
		}).bounds(this.width / 2 + 70, this.height - 28, 60, 20).build();
		backButton = Button.builder(Component.translatable("gui.birdwatch.back"), b -> {
			selected = null;
			init();
		}).bounds(this.width / 2 - 50, this.height - 28, 100, 20).build();
		prevButton.visible = pages > 1;
		nextButton.visible = pages > 1;
		closeButton.visible = true;
		backButton.visible = false;
		this.addRenderableWidget(closeButton);
		this.addRenderableWidget(prevButton);
		this.addRenderableWidget(nextButton);
		this.addRenderableWidget(backButton);
	}

	private List<String> currentPageIds() {
		List<String> ids = BestiaryRegistry.allIds();
		int from = page * PAGE_SIZE;
		return ids.subList(from, Math.min(from + PAGE_SIZE, ids.size()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		Minecraft mc = Minecraft.getInstance();
		Component title = this.getTitle();
		graphics.text(mc.font, title, this.width / 2 - mc.font.width(title) / 2, 8, 0xFFFFFFFF);

		if (selected != null) {
			drawDetail(graphics, mc, selected);
			return;
		}
		List<String> ids = currentPageIds();
		int startX = this.width / 2 - (COLS * 140 + (COLS - 1) * 10) / 2;
		int startY = 40;
		for (int i = 0; i < ids.size(); i++) {
			int x = startX + (i % COLS) * 150;
			int y = startY + (i / COLS) * (ROW_H + 6);
			boolean unlocked = BestiaryProgress.isUnlocked(ids.get(i));
			// 条目底色:未解锁深色 / 已解锁浅绿
			graphics.fill(x, y, x + 140, y + ROW_H, unlocked ? 0xFF1A3A1A : 0xFF222222);
			graphics.outline(x, y, 140, ROW_H, 0xFF444444);
			Component name = unlocked ? entityName(ids.get(i))
				: Component.literal("???");
			graphics.text(mc.font, name, x + 8, y + ROW_H / 2 - 5, unlocked ? 0xFFAAFFAA : 0xFF888888);
		}
		int pages = Math.max(1, (BestiaryRegistry.allIds().size() + PAGE_SIZE - 1) / PAGE_SIZE);
		String line = Component.translatable("screen.birdwatch.bestiary.count",
			BestiaryProgress.unlocked().size(), BestiaryRegistry.allIds().size()).getString()
			+ "  " + (page + 1) + "/" + pages;
		graphics.text(mc.font, Component.literal(line),
			this.width / 2 - mc.font.width(line) / 2, this.height - 46, 0xFF888888);
	}

	private void drawDetail(GuiGraphicsExtractor graphics, Minecraft mc, String entityId) {
		boolean unlocked = BestiaryProgress.isUnlocked(entityId);
		Component name = unlocked ? entityName(entityId) : Component.literal("???");
		graphics.text(mc.font, name, 40, 34, unlocked ? 0xFFFFFFFF : 0xFF888888);
		graphics.text(mc.font,
			Component.translatable(unlocked ? "screen.birdwatch.bestiary.unlocked"
				: "screen.birdwatch.bestiary.locked"),
			40, 50, unlocked ? 0xFF55FF55 : 0xFF888888);
		// 习性文案(未解锁也展示部分提示)
		if (unlocked) {
			Component desc = Component.translatable("bestiary.birdwatch." + entityId + ".desc");
			graphics.text(mc.font, desc, 40, 70, 0xFFCCCCCC);
		} else {
			Component hint = Component.translatable("screen.birdwatch.bestiary.locked_hint");
			graphics.text(mc.font, hint, 40, 70, 0xFF888888);
		}
		backButton.visible = true;
		closeButton.visible = false;
		prevButton.visible = false;
		nextButton.visible = false;
	}

	/** 原版实体显示名(EntityType 翻译键,原版自带本地化) */
	private static Component entityName(String entityId) {
		return BestiaryRegistry.typeOf(entityId)
			.map(EntityType::getDescriptionId)
			.map(Component::translatable)
			.orElse(Component.literal(entityId));
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (selected != null && event.isEscape()) {
			selected = null;
			init();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
		if (super.mouseClicked(event, bl)) {
			return true;
		}
		if (event.button() != 0 || selected != null) {
			return false;
		}
		double mx = event.x();
		double my = event.y();
		List<String> ids = currentPageIds();
		int startX = this.width / 2 - (COLS * 140 + (COLS - 1) * 10) / 2;
		for (int i = 0; i < ids.size(); i++) {
			int x = startX + (i % COLS) * 150;
			int y = 40 + (i / COLS) * (ROW_H + 6);
			if (mx >= x && mx < x + 140 && my >= y && my < y + ROW_H) {
				selected = ids.get(i);
				init();
				return true;
			}
		}
		return false;
	}
}
