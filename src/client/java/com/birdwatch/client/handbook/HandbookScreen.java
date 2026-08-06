package com.birdwatch.client.handbook;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.bird.BirdSpecies;
import com.birdwatch.bird.SpeciesRegistry;
import com.birdwatch.client.PhotoIndex;
import com.birdwatch.item.PhotoPrintItem;
import com.birdwatch.registry.ModItems;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 观鸟图鉴界面(M2a:单物种条目)。
 *
 * 左栏:物种名 + 预览图(未解锁为干净剪影)+ 照片槽(居中于预览下方,点击贴入印刷照片);
 * 右栏:学名 / 档位与最高分 / 习性 / 拍摄建议,内容超出时右侧出现滚动条(滚轮滚动);
 * 底部一行工具条:上一页/下一页(关闭按钮两侧)+ 搜索框/搜索按钮(禁用预留,M4 启用)。
 */
public class HandbookScreen extends Screen {
	private static final int PANEL_W_MAX = 820;  // 两栏总宽上限(含间距)
	private static final int COL_GAP = 16;       // 栏间距
	private static final int PANEL_TOP = 22;
	private static final int LINE_H = 10;        // 右栏文本行高

	/** 图鉴照片纹理缓存(photo+crop → 纹理与原始尺寸,供等比显示) */
	private final Map<String, PhotoTex> textureCache = new HashMap<>();

	/** 已注册的照片纹理及其像素尺寸 */
	private record PhotoTex(Identifier id, int w, int h) {
	}
	/** 物种列表(页序 = SpeciesRegistry.all() 注册序)与当前页 */
	private final List<BirdSpecies> speciesList;
	private int pageIndex;
	/** 搜索关键字(空 = 显示全部;实时过滤 id/中文名/学名) */
	private String searchQuery = "";
	private Button closeButton;
	private Button prevButton;
	private Button nextButton;
	private Button searchButton;
	private EditBox searchBox;
	/** 右栏滚动偏移(px)与内容总高(渲染时更新) */
	private double rightScroll;
	private int rightTotalH;
	/** 屏幕内提示(贴入结果;聊天消息会被界面遮挡,故绘制于界面顶部) */
	private String notice;
	private long noticeUntil;

	public HandbookScreen() {
		super(Component.translatable("screen.birdwatch.handbook"));
		// M4a:遍历 SpeciesRegistry 生成条目列表,翻页切换
		this.speciesList = SpeciesRegistry.all();
		this.pageIndex = 0;
	}

	/** 当前可见物种(按搜索过滤;空查询 = 全部);页序 = 注册序 */
	private List<BirdSpecies> visibleSpecies() {
		if (searchQuery.isBlank()) {
			return speciesList;
		}
		String q = searchQuery.trim().toLowerCase();
		return speciesList.stream().filter(s -> {
			if (s.id().toLowerCase().contains(q)) {
				return true;
			}
			// 中文名/学名按当前语言匹配
			for (String field : new String[]{"name", "scientific"}) {
				if (Component.translatable("handbook.birdwatch." + s.id() + "." + field)
					.getString().toLowerCase().contains(q)) {
					return true;
				}
			}
			return false;
		}).toList();
	}

	/** 当前页物种 id */
	private String speciesId() {
		return visibleSpecies().get(pageIndex).id();
	}

	/** 当前页物种 */
	private BirdSpecies species() {
		return visibleSpecies().get(pageIndex);
	}

	/** 图鉴条目文案 key:handbook.birdwatch.<物种>.<字段> */
	private String speciesKey(String field) {
		return "handbook.birdwatch." + speciesId() + "." + field;
	}

	/** 翻页按钮可用态(首尾页对应按钮禁用);点击翻页后必须调用,否则状态滞后 */
	private void refreshPaging() {
		prevButton.active = pageIndex > 0;
		nextButton.active = pageIndex < visibleSpecies().size() - 1;
	}

	@Override
	protected void init() {
		super.init();
		clearWidgets();
		// 底部一行工具条(紧凑):上一页 | 下一页 | 关闭 | 搜索框 | 搜索按钮
		int y = this.height - 28;
		prevButton = Button.builder(Component.translatable("gui.birdwatch.prev_page"), b -> {
			if (pageIndex > 0) {
				pageIndex--;
				rightScroll = 0;
				refreshPaging();
			}
		}).bounds(this.width / 2 - 190, y, 60, 20).build();
		nextButton = Button.builder(Component.translatable("gui.birdwatch.next_page"), b -> {
			if (pageIndex < speciesList.size() - 1) {
				pageIndex++;
				rightScroll = 0;
				refreshPaging();
			}
		}).bounds(this.width / 2 - 122, y, 60, 20).build();
		closeButton = Button.builder(Component.translatable("gui.birdwatch.close"), b -> this.onClose())
			.bounds(this.width / 2 - 50, y, 100, 20).build();
		int sw = Math.min(180, this.width / 2 - 130);
		searchBox = new EditBox(Minecraft.getInstance().font, this.width / 2 + 60, y + 1, sw, 18,
			Component.translatable("screen.birdwatch.handbook.search_hint"));
		// 实时过滤:id / 中文名 / 学名;过滤后回到第一页
		searchBox.setResponder(text -> {
			searchQuery = text;
			pageIndex = 0;
			rightScroll = 0;
			refreshPaging();
		});
		searchButton = Button.builder(Component.translatable("screen.birdwatch.handbook.search"),
			b -> {
			}).bounds(this.width / 2 + 64 + sw, y, 50, 20).build();
		// 翻页 M4a 启用(页序 = 注册序);搜索 M4a 启用(实时过滤)
		refreshPaging();
		searchButton.active = true;
		searchBox.setFocused(false);
		searchBox.active = true;
		this.addRenderableWidget(prevButton);
		this.addRenderableWidget(nextButton);
		this.addRenderableWidget(closeButton);
		this.addRenderableWidget(searchBox);
		this.addRenderableWidget(searchButton);
	}

	@Override
	public void onClose() {
		HandbookProgress.save();
		super.onClose();
	}

	// ------------------------------------------------------------------
	// 数据
	// ------------------------------------------------------------------

	/** 该物种照片最高分(扫描照片元数据) */
	private int maxScore() {
		int max = 0;
		for (PhotoIndex.PhotoRecord r : PhotoIndex.list()) {
			for (Map<String, Object> bird : birdsOf(r)) {
				if (speciesId().equals(bird.get("species"))) {
					Object score = bird.get("score");
					if (score instanceof Number n) {
						max = Math.max(max, n.intValue());
					}
				}
			}
		}
		return max;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> birdsOf(PhotoIndex.PhotoRecord r) {
		Object birds = r.data().get("birds");
		if (birds instanceof List<?> list) {
			return (List<Map<String, Object>>) list.stream().filter(o -> o instanceof Map).toList();
		}
		return List.of();
	}

	// ------------------------------------------------------------------
	// 布局坐标(全部自适应窗口;槽位永远在面板内,工具条在面板下方)
	// ------------------------------------------------------------------

	private int panelWidth() {
		return Math.min(PANEL_W_MAX, this.width - 40);
	}

	private int columnWidth() {
		return (panelWidth() - COL_GAP) / 2;
	}

	private int panelLeft() {
		return this.width / 2 - panelWidth() / 2;
	}

	private int rightX() {
		return panelLeft() + panelWidth() - columnWidth();
	}

	private int previewW() {
		return columnWidth() - 20;
	}

	/** 预览高:16:9 为主,受面板高压缩(预览底 + 提示行不溢出面板) */
	private int previewH() {
		int byRatio = previewW() * 9 / 16;
		return Math.min(byRatio, Math.max(60, panelHeight() - 40));
	}

	private int previewTop() {
		return PANEL_TOP + 20;
	}

	/**
	 * 预览区 = 贴入槽(点击大图贴入印刷照片解锁):
	 * {x, y, w, h},渲染与点击共用同一坐标源。
	 */
	private int[] previewRect() {
		return new int[]{panelLeft() + 10, previewTop(), previewW(), previewH()};
	}

	/**
	 * 面板高 = 窗口可用高(无下限):面板底恒等于按钮行(height-28)上方 22px,
	 * 任何窗口尺寸下相对位置固定;内容(预览/文本)随面板高缩放,右栏靠滚动条容纳。
	 */
	private int panelHeight() {
		return this.height - 50 - PANEL_TOP;
	}

	private int panelBottom() {
		return PANEL_TOP + panelHeight();
	}

	// ------------------------------------------------------------------
	// 渲染
	// ------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		// 搜索无结果:绘制空态后直接结束(后续渲染依赖当前物种,必须守卫)
		if (visibleSpecies().isEmpty()) {
			int pl = panelLeft();
			int colW = columnWidth();
			graphics.fill(pl, PANEL_TOP, pl + colW, panelBottom(), 0x99000000);
			graphics.fill(rightX(), PANEL_TOP, rightX() + colW, panelBottom(), 0x99000000);
			Component empty = Component.translatable("screen.birdwatch.handbook.no_result");
			graphics.text(mc.font, empty, this.width / 2 - mc.font.width(empty) / 2,
				this.height / 2 - 6, 0xFF666666);
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			return;
		}
		boolean unlocked = HandbookProgress.isUnlocked(speciesId());
		int pl = panelLeft();
		int rx = rightX();
		int colW = columnWidth();
		int pW = previewW();
		int pH = previewH();

		// 两栏背景(左右对称,顶/底一致)
		graphics.fill(pl, PANEL_TOP, pl + colW, panelBottom(), 0x99000000);
		graphics.fill(rx, PANEL_TOP, rx + colW, panelBottom(), 0x99000000);

		// 标题(带页码,如「观鸟图鉴 1/3」;搜索时页码基于过滤结果)
		Component title = this.getTitle().copy()
			.append(" " + (pageIndex + 1) + "/" + visibleSpecies().size());
		graphics.text(mc.font, title, this.width / 2 - mc.font.width(title) / 2, 8, 0xFFFFFFFF);

		// 屏幕内提示(贴入成功/失败),显示 3 秒
		if (notice != null && mc.level != null && mc.level.getGameTime() < noticeUntil) {
			Component noticeText = Component.literal(notice);
			graphics.text(mc.font, noticeText, this.width / 2 - mc.font.width(noticeText) / 2, 20, 0xFF55FF55);
		} else {
			notice = null;
		}

		// ---- 左栏:物种名 + 预览区(= 贴入槽,点击大图贴入印刷照片) ----
		graphics.text(mc.font, Component.translatable(speciesKey("name")),
			pl + 10, PANEL_TOP + 6, 0xFFFFFFFF);
		int[] pv = previewRect();
		int px = pv[0], py = pv[1];
		boolean hasPhoto = false;
		if (unlocked) {
			String slot = HandbookProgress.slotPhoto(speciesId());
			PhotoTex tex = slot != null ? textureForPhoto(slot, HandbookProgress.slotCrop(speciesId())) : null;
			if (tex != null) {
				// 按照片实际比例显示(letterbox 居中,不拉伸变形)
				double ratio = Math.min((double) pW / tex.w(), (double) pH / tex.h());
				int dw = Math.max(1, (int) (tex.w() * ratio));
				int dh = Math.max(1, (int) (tex.h() * ratio));
				int dx = px + (pW - dw) / 2;
				int dy = py + (pH - dh) / 2;
				graphics.blit(tex.id(), dx, dy, dx + dw, dy + dh, 0.0f, 1.0f, 0.0f, 1.0f);
				hasPhoto = true;
			} else {
				// 解锁但无照片(异常态):显示物种纹理
				graphics.blit(speciesTexture(), px, py, px + pW, py + pH, 0.0f, 1.0f, 0.0f, 1.0f);
			}
		} else {
			// 未解锁:干净的深色剪影(不显示贴图)
			graphics.fill(px, py, px + pW, py + pH, 0xFF141414);
			graphics.text(mc.font, Component.literal("???"), px + pW / 2 - 14, py + pH / 2 - 6, 0xFF666666);
			Component lockHint = Component.translatable("screen.birdwatch.handbook.preview_locked");
			graphics.text(mc.font, lockHint, px + pW / 2 - mc.font.width(lockHint) / 2, py + pH / 2 + 8, 0xFF444444);
		}
		// 金色边框:提示整块预览区可点击贴入
		graphics.outline(px - 1, py - 1, pW + 2, pH + 2, 0xFF886600);
		// 预览区底部提示行:未贴入时提示点击,已贴入显示解锁徽章
		if (hasPhoto) {
			graphics.text(mc.font, Component.translatable("screen.birdwatch.handbook.unlocked"),
				px, py + pH + 4, 0xFF55FF55);
		} else {
			graphics.text(mc.font, Component.translatable("screen.birdwatch.handbook.slot.empty"),
				px, py + pH + 4, 0xFFAAAAAA);
		}

		// ---- 右栏:滚动文本区 ----
		int textW = colW - 28;
		int visTop = PANEL_TOP + 4;
		int visBottom = panelBottom() - 4;
		List<String> lines = buildRightLines(mc, unlocked, textW);
		rightTotalH = lines.size() * LINE_H + 8;
		rightScroll = clampScroll(rightScroll);
		int y = visTop - (int) rightScroll;
		for (String line : lines) {
			if (y + LINE_H >= visTop && y <= visBottom) { // 裁剪绘制
				graphics.text(mc.font, Component.literal(line), rx + 10, y, lineColor(line));
			}
			y += LINE_H;
		}
		// 滚动条(内容超出时才显示)
		if (rightTotalH > visBottom - visTop) {
			drawScrollbar(graphics, rx + colW - 6, visTop, visBottom - visTop);
		}

		// 按钮最后渲染(最上层)
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/** 右栏文本行(预计算;标题行特殊颜色) */
	private List<String> buildRightLines(Minecraft mc, boolean unlocked, int textW) {
		List<String> lines = new ArrayList<>();
		String sci = unlocked ? Component.translatable(speciesKey("scientific")).getString() : "???";
		lines.add("[学名] " + sci);
		int score = maxScore();
		String tierKey = score >= 95 ? "handbook.birdwatch.tier.perfect"
			: score >= 80 ? "handbook.birdwatch.tier.excellent"
			: score >= 60 ? "handbook.birdwatch.tier.unlock" : "handbook.birdwatch.tier.none";
		lines.add("[评分] " + score + "  " + Component.translatable(tierKey).getString());
		lines.add("");
		lines.add("[习性]");
		lines.addAll(wrapText(mc, Component.translatable(speciesKey("habitat")).getString(), textW));
		lines.add("");
		lines.add("[拍摄建议]");
		lines.addAll(wrapText(mc, Component.translatable(speciesKey("tip")).getString(), textW));
		return lines;
	}

	/** 行颜色:[标题] 蓝色,其余灰色 */
	private int lineColor(String line) {
		return line.startsWith("[") ? 0xFF88CCFF : 0xFFBBBBBB;
	}

	/** 中文友好换行:逐字符累加,超宽回退最近空格断点 */
	private static List<String> wrapText(Minecraft mc, String full, int maxW) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		int lastSpace = -1;
		for (int i = 0; i < full.length(); i++) {
			char c = full.charAt(i);
			line.append(c);
			if (c == ' ') {
				lastSpace = line.length();
			}
			if (mc.font.width(line.toString()) > maxW) {
				int cut = lastSpace > 0 ? lastSpace : Math.max(1, line.length() - 1);
				lines.add(line.substring(0, cut));
				line.delete(0, cut);
				lastSpace = -1;
			}
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
	}

	private double clampScroll(double value) {
		int visH = panelBottom() - PANEL_TOP - 8;
		double max = Math.max(0, rightTotalH - visH);
		return Math.max(0, Math.min(value, max));
	}

	private void drawScrollbar(GuiGraphicsExtractor g, int x, int y, int trackH) {
		int visH = panelBottom() - PANEL_TOP - 8;
		g.fill(x, y, x + 3, y + trackH, 0x44000000);
		double max = Math.max(1, rightTotalH - visH);
		int thumbH = Math.max(14, (int) (trackH * (visH / (double) rightTotalH)));
		int thumbY = y + (int) (rightScroll / max * (trackH - thumbH));
		g.fill(x, thumbY, x + 3, thumbY + thumbH, 0xAAFFFFFF);
	}

	// ------------------------------------------------------------------
	// 交互
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
		if (super.mouseClicked(event, bl)) {
			return true;
		}
		if (event.button() != 0) {
			return false;
		}
		// 点击预览区(= 贴入槽)贴入印刷照片
		int[] pv = previewRect();
		double mx = event.x();
		double my = event.y();
		if (mx >= pv[0] && mx < pv[0] + pv[2] && my >= pv[1] && my < pv[1] + pv[3]) {
			tryPasteFromInventory();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int rx = rightX();
		int colW = columnWidth();
		if (mouseX >= rx && mouseX < rx + colW && mouseY >= PANEL_TOP && mouseY < panelBottom()) {
			rightScroll = clampScroll(rightScroll - scrollY * 10);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/** 从背包找该物种的印刷照片(评分≥60);多张时弹选择界面,单张直接贴入 */
	private void tryPasteFromInventory() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		java.util.List<ItemStack> candidates = new java.util.ArrayList<>();
		for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (!stack.is(ModItems.PHOTO_PRINT)) {
				continue;
			}
			var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
			if (data == null) {
				continue;
			}
			var tag = data.copyTag();
			if (!speciesId().equals(tag.getString(PhotoPrintItem.KEY_SPECIES).orElse(""))) {
				continue;
			}
			if (tag.getInt(PhotoPrintItem.KEY_SCORE).orElse(0) < 60) {
				continue;
			}
			candidates.add(stack);
		}
		if (candidates.isEmpty()) {
			showNotice(Component.translatable("screen.birdwatch.handbook.no_print",
				Component.translatable(speciesKey("name"))).getString());
			return;
		}
		if (candidates.size() == 1) {
			applyPrint(candidates.get(0));
			return;
		}
		// 多张候选:打开选择界面
		Minecraft.getInstance().setScreenAndShow(new PhotoSelectScreen(candidates, speciesId(), this));
	}

	/** 贴入指定印刷照片:旧照片返还 + 解锁 + 成就授奖(供本界面与选择界面调用) */
	static void applyPrint(ItemStack print, String speciesId, net.minecraft.client.gui.screens.Screen returnScreen) {
		var data = print.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		if (data == null) {
			return;
		}
		var tag = data.copyTag();
		String photo = tag.getString(PhotoPrintItem.KEY_PHOTO).orElse("");
		String crop = tag.getString(PhotoPrintItem.KEY_CROP).orElse("");
		Minecraft mc = Minecraft.getInstance();
		// 旧照片返还/新照片消耗全部走服务端(客户端直接改背包是幽灵物品)
		String oldPhoto = HandbookProgress.slotPhoto(speciesId);
		BirdWatchMod.LOGGER.info("[Print] 图鉴贴入 species={} photo={}",
			speciesId, photo);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
			new com.birdwatch.network.ModNetworking.HandbookUnlockPayload(speciesId, photo,
				oldPhoto != null ? oldPhoto : "",
				oldPhoto != null ? HandbookProgress.slotCrop(speciesId) : ""));
		// 解锁 + 进度保存(客户端文件)
		HandbookProgress.unlock(speciesId, photo, crop);
		HandbookProgress.save();
		// 返回图鉴界面并提示
		if (returnScreen instanceof HandbookScreen handbook) {
			handbook.showNotice(Component.translatable("screen.birdwatch.handbook.unlocked_msg",
				Component.translatable("handbook.birdwatch." + speciesId + ".name")).getString());
		}
	}

	/** 直接贴入(单张候选) */
	private void applyPrint(ItemStack print) {
		applyPrint(print, speciesId(), this);
		init();
	}

	/** 由照片路径(相对 photos)+ 裁剪矩形重建印刷照片物品(旧照片返还用);评分从照片元数据读取 */
	private static ItemStack createPrintFromPhoto(String photoPath, String crop) {
		try {
			Path json = com.birdwatch.client.photo.PhotoStorage.photosRoot()
				.resolve(photoPath).resolveSibling(
					java.nio.file.Path.of(photoPath).getFileName().toString().replace(".png", ".json"));
			if (!Files.exists(json)) {
				return null;
			}
			String raw = Files.readString(json, java.nio.charset.StandardCharsets.UTF_8);
			Map<?, ?> data = new com.google.gson.Gson().fromJson(raw, Map.class);
			String species = "";
			int score = 0;
			String tier = "";
			Object birds = data.get("birds");
			if (birds instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
				species = String.valueOf(m.get("species"));
				if (m.get("score") instanceof Number n) {
					score = n.intValue();
				}
				Object t = m.get("tier");
				tier = t != null ? String.valueOf(t) : "";
			}
			ItemStack print = new ItemStack(ModItems.PHOTO_PRINT);
			final String fPhoto = photoPath;
			final String fSpecies = species;
			final int fScore = score;
			final String fTier = tier;
			net.minecraft.world.item.component.CustomData.update(
				net.minecraft.core.component.DataComponents.CUSTOM_DATA, print, tag -> {
					tag.putString(PhotoPrintItem.KEY_PHOTO, fPhoto);
					tag.putString(PhotoPrintItem.KEY_SPECIES, fSpecies);
					tag.putInt(PhotoPrintItem.KEY_SCORE, fScore);
					tag.putString(PhotoPrintItem.KEY_TIER, fTier);
					tag.putString(PhotoPrintItem.KEY_CROP, crop == null ? "" : crop);
				});
			return print;
		} catch (Exception e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 旧照片返还构造失败 {}", photoPath, e);
			return null;
		}
	}

	/** 屏幕内提示(3 秒) */
	private void showNotice(String text) {
		notice = text;
		if (Minecraft.getInstance().level != null) {
			noticeUntil = Minecraft.getInstance().level.getGameTime() + 60;
		}
	}

	// ------------------------------------------------------------------
	// 纹理
	// ------------------------------------------------------------------

	private Identifier speciesTexture() {
		return species().textureId();
	}

	/** 照片预览:读照片 PNG,按印刷裁剪矩形裁切后注册纹理(缓存 key 含裁剪);返回纹理与尺寸 */
	private PhotoTex textureForPhoto(String relativePath, String crop) {
		String cacheKey = relativePath + "#" + (crop == null ? "" : crop);
		PhotoTex cached = textureCache.get(cacheKey);
		if (cached != null) {
			return cached;
		}
		Path root = com.birdwatch.client.photo.PhotoStorage.photosRoot();
		Path png = root.resolve(relativePath);
		if (!Files.exists(png)) {
			return null;
		}
		double[] c = parseCrop(crop);
		try (InputStream in = Files.newInputStream(png)) {
			NativeImage source = NativeImage.read(in);
			int w = Math.max(1, (int) Math.round(source.getWidth() * c[2]));
			int h = Math.max(1, (int) Math.round(source.getHeight() * c[3]));
			int sx = (int) Math.round(source.getWidth() * c[0]);
			int sy = (int) Math.round(source.getHeight() * c[1]);
			NativeImage image = new NativeImage(w, h, true);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					image.setPixel(x, y, source.getPixel(Math.min(source.getWidth() - 1, sx + x),
						Math.min(source.getHeight() - 1, sy + y)));
				}
			}
			source.close();
			Identifier id = Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "hb_" + cacheKey.hashCode());
			Minecraft.getInstance().getTextureManager().register(id,
				new DynamicTexture(() -> "birdwatch handbook " + relativePath, image));
			PhotoTex tex = new PhotoTex(id, w, h);
			textureCache.put(cacheKey, tex);
			return tex;
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 无法加载图鉴照片 {}", png, e);
		}
		return null;
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
}
