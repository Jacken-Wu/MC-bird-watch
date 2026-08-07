package com.birdwatch.client.handbook;

import com.birdwatch.bird.BestiaryRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

/**
 * 生物图鉴界面(M4b):原版常见生物清单,拍照解锁。
 * 复用观鸟图鉴的翻页/搜索系统:每页 8 个(4 行 2 列),未解锁也显示名字(暗色);
 * 解锁状态来自服务端附件(C2S 查询 → S2C 回传),打开时请求。
 */
public class BestiaryScreen extends Screen {
	/** 每页 8 个:4 行 2 列 */
	private static final int ROWS = 4;
	private static final int COLS = 2;
	private static final int PAGE_SIZE = ROWS * COLS;
	private static final int ENTRY_W = 180;
	private static final int ENTRY_H = 36;
	private static final int GAP = 8;

	private final List<String> entityIds = new ArrayList<>(BestiaryRegistry.allIds());
	private String searchQuery = "";
	private int pageIndex;
	private String selected;
	private Button closeButton;
	private Button prevButton;
	private Button nextButton;
	private Button backButton;
	private Button searchButton;
	private EditBox searchBox;

	public BestiaryScreen() {
		super(Component.translatable("screen.birdwatch.bestiary"));
		BestiaryProgress.request();
	}

	/** 搜索过滤:id / 本地化名;空查询返回全部 */
	private List<String> visibleIds() {
		if (searchQuery.isBlank()) {
			return entityIds;
		}
		String q = searchQuery.trim().toLowerCase();
		return entityIds.stream().filter(id -> {
			if (id.toLowerCase().contains(q)) {
				return true;
			}
			// 按当前语言匹配显示名
			return BestiaryRegistry.typeOf(id)
				.map(EntityType::getDescriptionId)
				.map(Component::translatable)
				.map(c -> c.getString().toLowerCase().contains(q))
				.orElse(false);
		}).toList();
	}

	/** 当前页条目(8 个) */
	private List<String> currentPageIds() {
		List<String> visible = visibleIds();
		int from = pageIndex * PAGE_SIZE;
		if (from >= visible.size()) {
			return List.of();
		}
		return visible.subList(from, Math.min(from + PAGE_SIZE, visible.size()));
	}

	/** 翻页按钮可用态;点击翻页后必须调用 */
	private void refreshPaging() {
		int pages = Math.max(1, (visibleIds().size() + PAGE_SIZE - 1) / PAGE_SIZE);
		prevButton.active = pageIndex > 0;
		nextButton.active = pageIndex < pages - 1;
	}

	@Override
	protected void init() {
		super.init();
		clearWidgets();
		// 底部工具条(与观鸟图鉴一致):上一页 | 下一页 | 关闭 | 搜索框
		int y = this.height - 28;
		prevButton = Button.builder(Component.translatable("gui.birdwatch.prev_page"), b -> {
			if (pageIndex > 0) {
				pageIndex--;
				refreshPaging();
			}
		}).bounds(this.width / 2 - 190, y, 60, 20).build();
		nextButton = Button.builder(Component.translatable("gui.birdwatch.next_page"), b -> {
			pageIndex++;
			refreshPaging();
		}).bounds(this.width / 2 - 122, y, 60, 20).build();
		closeButton = Button.builder(Component.translatable("gui.birdwatch.close"), b -> this.onClose())
			.bounds(this.width / 2 - 50, y, 100, 20).build();
		int sw = Math.min(180, this.width / 2 - 130);
		searchBox = new EditBox(Minecraft.getInstance().font, this.width / 2 + 60, y + 1, sw, 18,
			Component.translatable("screen.birdwatch.handbook.search_hint"));
		// 实时过滤:id / 本地化名;过滤后回到第一页
		searchBox.setResponder(text -> {
			searchQuery = text;
			pageIndex = 0;
			refreshPaging();
		});
		searchButton = Button.builder(Component.translatable("screen.birdwatch.handbook.search"), b -> {
		}).bounds(this.width / 2 + 64 + sw, y, 50, 20).build();
		// 详情页返回按钮
		backButton = Button.builder(Component.translatable("gui.birdwatch.back"), b -> {
			selected = null;
			init();
		}).bounds(this.width / 2 - 50, y, 100, 20).build();
		refreshPaging();
		searchButton.active = true;
		searchBox.setFocused(false);
		searchBox.active = true;
		backButton.visible = false;
		this.addRenderableWidget(prevButton);
		this.addRenderableWidget(nextButton);
		this.addRenderableWidget(closeButton);
		this.addRenderableWidget(searchBox);
		this.addRenderableWidget(searchButton);
		this.addRenderableWidget(backButton);
	}

	/** 网格起始 X(居中):4 行 2 列 */
	private int gridStartX() {
		return this.width / 2 - (COLS * ENTRY_W + (COLS - 1) * GAP) / 2;
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
		if (ids.isEmpty() && !visibleIds().isEmpty()) {
			pageIndex = 0;
			ids = currentPageIds();
		}
		int startX = gridStartX();
		int startY = 40;
		for (int i = 0; i < ids.size(); i++) {
			int x = startX + (i % COLS) * (ENTRY_W + GAP);
			int y = startY + (i / COLS) * (ENTRY_H + GAP);
			boolean unlocked = BestiaryProgress.isUnlocked(ids.get(i));
			// 条目底色:未解锁深色 / 已解锁浅绿
			graphics.fill(x, y, x + ENTRY_W, y + ENTRY_H, unlocked ? 0xFF1A3A1A : 0xFF222222);
			graphics.outline(x, y, ENTRY_W, ENTRY_H, unlocked ? 0xFF3A7A3A : 0xFF444444);
			// 未解锁也显示名字(暗色);已解锁亮绿
			Component name = entityName(ids.get(i));
			graphics.text(mc.font, name, x + 10, y + ENTRY_H / 2 - 5,
				unlocked ? 0xFFAAFFAA : 0xFF777777);
		}
		if (ids.isEmpty()) {
			Component empty = Component.translatable("screen.birdwatch.handbook.no_result");
			graphics.text(mc.font, empty, this.width / 2 - mc.font.width(empty) / 2,
				this.height / 2, 0xFF888888);
		}
		// 底部:解锁计数 + 页码
		List<String> visible = visibleIds();
		int pages = Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		String line = Component.translatable("screen.birdwatch.bestiary.count",
			BestiaryProgress.unlocked().size(), BestiaryRegistry.allIds().size()).getString()
			+ "  " + (pageIndex + 1) + "/" + pages;
		graphics.text(mc.font, Component.literal(line),
			this.width / 2 - mc.font.width(line) / 2, this.height - 46, 0xFF888888);
	}

	private void drawDetail(GuiGraphicsExtractor graphics, Minecraft mc, String entityId) {
		boolean unlocked = BestiaryProgress.isUnlocked(entityId);
		Component name = entityName(entityId);
		graphics.text(mc.font, name, 40, 34, unlocked ? 0xFFFFFFFF : 0xFF888888);
		graphics.text(mc.font,
			Component.translatable(unlocked ? "screen.birdwatch.bestiary.unlocked"
				: "screen.birdwatch.bestiary.locked"),
			40, 50, unlocked ? 0xFF55FF55 : 0xFF888888);
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
		searchBox.visible = false;
		searchButton.visible = false;
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
		int startX = gridStartX();
		for (int i = 0; i < ids.size(); i++) {
			int x = startX + (i % COLS) * (ENTRY_W + GAP);
			int y = 40 + (i / COLS) * (ENTRY_H + GAP);
			if (mx >= x && mx < x + ENTRY_W && my >= y && my < y + ENTRY_H) {
				selected = ids.get(i);
				init();
				return true;
			}
		}
		return false;
	}
}
