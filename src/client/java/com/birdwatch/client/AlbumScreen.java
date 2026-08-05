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
 * 相册屏幕(M1 雏形):照片网格(自适应窗口宽度)+ 详情页。
 * 缩略图用完整分辨率 NativeImage 注册纹理(M2 起做下采样优化)。
 */
public class AlbumScreen extends Screen {
	private static final int MAX_PHOTOS = 30;
	/** 网格边距与间隙 */
	private static final int MARGIN = 20;
	private static final int GAP = 12;
	/** 固定 5 列(用户约定);行数按窗口高度自适应(最多 3 行) */
	private static final int COLS = 5;
	private static final int MAX_ROWS = 3;
	private static final int MAX_THUMB = 100;
	/** 垂直方向缩略图下限(再小则行数已减无可减) */
	private static final int THUMB_MIN = 40;

	private final Map<String, Identifier> textureCache = new HashMap<>();
	private List<PhotoIndex.PhotoRecord> photos;
	private PhotoIndex.PhotoRecord selected;
	private int page;
	/** 自适应网格参数(init 时按窗口尺寸计算) */
	private int thumb = MAX_THUMB;
	private int rows = MAX_ROWS;
	private Button backButton;
	private Button closeButton;
	private Button prevButton;
	private Button nextButton;

	public AlbumScreen() {
		super(Component.translatable("screen.birdwatch.album"));
	}

	@Override
	protected void init() {
		super.init();
		clearWidgets(); // 重建按钮前清空 children,避免重复注册
		photos = PhotoIndex.list();
		if (photos.size() > MAX_PHOTOS) {
			photos = photos.subList(0, MAX_PHOTOS);
		}
		updateGridMetrics();
		hoveredIndex = -1;
		int totalPages = totalPages();
		page = Math.max(0, Math.min(page, totalPages - 1));
		backButton = Button.builder(Component.translatable("gui.birdwatch.back"), b -> {
			selected = null;
			init();
		}).bounds(this.width / 2 - 50, this.height - 28, 100, 20).build();
		closeButton = Button.builder(Component.translatable("gui.birdwatch.close"), b -> this.onClose())
			.bounds(this.width / 2 - 50, this.height - 28, 100, 20).build();
		prevButton = Button.builder(Component.translatable("gui.birdwatch.prev_page"), b -> {
			page = Math.max(0, page - 1);
			init();
		}).bounds(this.width / 2 - 130, this.height - 28, 60, 20).build();
		nextButton = Button.builder(Component.translatable("gui.birdwatch.next_page"), b -> {
			page = Math.min(totalPages - 1, page + 1);
			init();
		}).bounds(this.width / 2 + 70, this.height - 28, 60, 20).build();
		boolean paged = photos.size() > pageSize();
		// visible/active 全部显式设置:隐藏按钮仍会被 isMouseOver 命中,
		// 漏设 visible 会导致隐形按钮抢走同区域点击(关闭按钮被返回按钮遮挡的根因)
		backButton.visible = false; // 仅大图页显示(drawDetail 中置 true)
		closeButton.visible = true;
		prevButton.visible = paged;
		nextButton.visible = paged;
		closeButton.active = true;
		prevButton.active = page > 0;
		nextButton.active = page < totalPages - 1;
		// 按钮必须注册进 children 才会渲染和响应点击(26.2 Screen 不自动注册)
		this.addRenderableWidget(backButton);
		this.addRenderableWidget(closeButton);
		this.addRenderableWidget(prevButton);
		this.addRenderableWidget(nextButton);
	}

	@Override
	public void onClose() {
		super.onClose();
		// 从取景器打开相册时:关闭后自动恢复取景器(无缝回到拍摄)
		if (CameraSession.shouldResumeViewfinderAfterAlbum()) {
			CameraSession.clearResumeViewfinder();
			CameraSession.get().enterViewfinder();
		}
	}

	/**
	 * 固定 5 列;缩略图按窗口宽高自适应,行数按窗口高度减少(最多 3 行):
	 * 水平不超宽,垂直不压到底部页码/按钮区(内容区 startY=40 ~ height-60)。
	 */
	private void updateGridMetrics() {
		int availW = this.width - MARGIN * 2;
		// 水平:5 列缩放到刚好不超宽(不设下限,极端窄窗口继续缩小)
		thumb = Math.min(MAX_THUMB, (availW - (COLS - 1) * GAP) / COLS);
		int availH = this.height - 40 - 60;
		rows = Math.min(MAX_ROWS, Math.max(2, (availH - GAP) / (THUMB_MIN + GAP)));
		int thumbV = (availH - (rows - 1) * GAP) / rows;
		thumb = Math.min(thumb, Math.max(THUMB_MIN, thumbV));
	}

	private int pageSize() {
		return COLS * rows;
	}

	private int totalPages() {
		return Math.max(1, (photos.size() + pageSize() - 1) / pageSize());
	}

	private List<PhotoIndex.PhotoRecord> currentPage() {
		int from = page * pageSize();
		return photos.subList(from, Math.min(from + pageSize(), photos.size()));
	}

	/** 网格起始 X(居中) */
	private int gridStartX() {
		return this.width / 2 - (COLS * thumb + (COLS - 1) * GAP) / 2;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		Minecraft minecraft = Minecraft.getInstance();

		if (selected == null) {
			// 标题(居中)
			Component title = this.getTitle();
			graphics.text(minecraft.font, title, this.width / 2 - minecraft.font.width(title) / 2, 8, 0xFFFFFFFF);
			if (photos.isEmpty()) {
				graphics.text(minecraft.font, Component.translatable("screen.birdwatch.album.empty"),
					this.width / 2 - 60, this.height / 2, 0xFF888888);
				return;
			}
			int startX = gridStartX();
			int startY = 40;
			List<PhotoIndex.PhotoRecord> pagePhotos = currentPage();
			for (int i = 0; i < pagePhotos.size(); i++) {
				int x = startX + (i % COLS) * (thumb + GAP);
				int y = startY + (i / COLS) * (thumb + GAP);
				// 26.2 blit 语义:(id, x1, y1, x2, y2, u0, u1, v0, v1),对角坐标 + UV 区间
				graphics.blit(textureFor(pagePhotos.get(i)), x, y, x + thumb, y + thumb, 0.0f, 1.0f, 0.0f, 1.0f);
			}
			// 悬停照片高亮(26.2 outline 语义:(x, y, width, height, color))
			if (hoveredIndex >= 0) {
				int x = startX + (hoveredIndex % COLS) * (thumb + GAP);
				int y = startY + (hoveredIndex / COLS) * (thumb + GAP);
				graphics.outline(x - 2, y - 2, thumb + 4, thumb + 4, 0xFFFFFFFF);
			}
			// 底部:照片总数 + 页码(同一行,居中;按钮行上方)
			String pageLine = Component.translatable("screen.birdwatch.album.count", photos.size()).getString()
				+ "  " + (page + 1) + "/" + totalPages();
			graphics.text(minecraft.font, Component.literal(pageLine),
				this.width / 2 - minecraft.font.width(pageLine) / 2, this.height - 46, 0xFF888888);
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
		graphics.blit(textureFor(record), x, y, x + imgW, y + imgH, 0.0f, 1.0f, 0.0f, 1.0f);

		// 参数行:光圈/快门/ISO/焦距(JSON 数值为 Double)
		Object aperture = record.data().getOrDefault("aperture", "?");
		Object shutter = record.data().getOrDefault("shutter", "?");
		Object iso = record.data().getOrDefault("iso", "?");
		Object focal = record.data().getOrDefault("focalLength", "?");
		String shutterStr = shutter instanceof Number n
			? "1/" + Math.round(1.0 / n.doubleValue())
			: String.valueOf(shutter);
		String focalStr = focal instanceof Number n ? (int) Math.round(n.doubleValue()) + "mm" : String.valueOf(focal);
		graphics.text(minecraft.font, Component.literal("f/" + aperture + "  " + shutterStr
			+ "  ISO " + iso + "  " + focalStr), x, y + imgH + 6, 0xFFCCCCCC);
		backButton.setX(this.width / 2 - 50);
		backButton.setY(this.height - 28);
		backButton.visible = true;
		closeButton.visible = false;
		prevButton.visible = false;
		nextButton.visible = false;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		// 大图页 ESC:层级返回网格页,而不是直接关闭相册
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
		double mouseX = event.x();
		double mouseY = event.y();
		if (photos != null && !photos.isEmpty()) {
			int startX = gridStartX();
			int startY = 40;
			List<PhotoIndex.PhotoRecord> pagePhotos = currentPage();
			for (int i = 0; i < pagePhotos.size(); i++) {
				int x = startX + (i % COLS) * (thumb + GAP);
				int y = startY + (i / COLS) * (thumb + GAP);
				if (mouseX >= x && mouseX < x + thumb && mouseY >= y && mouseY < y + thumb) {
					selected = pagePhotos.get(i);
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
		hoveredIndex = -1;
		if (selected == null && photos != null && !photos.isEmpty()) {
			int startX = gridStartX();
			int startY = 40;
			List<PhotoIndex.PhotoRecord> pagePhotos = currentPage();
			for (int i = 0; i < pagePhotos.size(); i++) {
				int x = startX + (i % COLS) * (thumb + GAP);
				int y = startY + (i / COLS) * (thumb + GAP);
				if (mouseX >= x && mouseX < x + thumb && mouseY >= y && mouseY < y + thumb) {
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
			// 26.2 构造器自动 createTexture + upload;register 仅入查找表
			Minecraft.getInstance().getTextureManager().register(id,
				new DynamicTexture(() -> "birdwatch photo " + record.name(), image));
			textureCache.put(record.name(), id);
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 无法加载照片纹理 {}", record.pngPath(), e);
		}
		return id;
	}

	private int hoveredIndex = -1;
}
