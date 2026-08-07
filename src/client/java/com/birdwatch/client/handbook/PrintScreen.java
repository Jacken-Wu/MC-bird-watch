package com.birdwatch.client.handbook;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.client.CameraSession;
import com.birdwatch.client.PhotoIndex;
import com.birdwatch.config.BirdWatchConfig;
import com.birdwatch.item.PhotoPrintItem;
import com.birdwatch.registry.ModItems;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 印刷界面(M2a):显示照片 + 可拖动裁剪框(不影响原图),
 * 确认印刷消耗 1 张纸,产出「印刷照片」物品(带裁剪矩形与评分 NBT)。
 */
public class PrintScreen extends Screen {
	private static final int BORDER = 8;       // 裁剪框边距(最小裁切)
	private static final int HANDLE = 6;       // 角柄大小

	private final PhotoIndex.PhotoRecord record;
	/** 返回屏幕(相册):关闭印刷后返回上一层,而非直接回到游戏/取景器 */
	private final Screen returnScreen;
	/** 返回相册后,相册关闭是否恢复取景器(继承自打开印刷前的相册状态) */
	private final boolean resumeViewfinderAfter;
	/** 裁剪矩形(归一化 0~1):x, y, w, h */
	private double cropX = 0;
	private double cropY = 0;
	private double cropW = 1;
	private double cropH = 1;
	/** 当前拖拽的角(-1 无) */
	private int dragCorner = -1;
	private Identifier textureId;
	private Button printButton;
	private Button closeButton;

	public PrintScreen(PhotoIndex.PhotoRecord record, Screen returnScreen, boolean resumeViewfinderAfter) {
		super(Component.translatable("screen.birdwatch.print"));
		this.record = record;
		this.returnScreen = returnScreen;
		this.resumeViewfinderAfter = resumeViewfinderAfter;
		loadTexture();
	}

	@Override
	public void onClose() {
		if (returnScreen != null) {
			// 返回相册并恢复其"关闭后恢复取景器"状态(相册最初由取景器 E 键打开时)
			CameraSession.setResumeViewfinderAfterAlbum(resumeViewfinderAfter);
			Minecraft.getInstance().setScreenAndShow(returnScreen);
			return;
		}
		super.onClose();
	}

	private void loadTexture() {
		textureId = Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "print_" + record.name().hashCode());
		try (InputStream in = Files.newInputStream(record.pngPath())) {
			NativeImage image = NativeImage.read(in);
			Minecraft.getInstance().getTextureManager().register(textureId,
				new DynamicTexture(() -> "birdwatch print " + record.name(), image));
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 印刷界面无法加载照片 {}", record.pngPath(), e);
		}
	}

	@Override
	protected void init() {
		super.init();
		clearWidgets();
		printButton = Button.builder(Component.translatable("screen.birdwatch.print.confirm"), b -> doPrint())
			.bounds(this.width / 2 - 110, this.height - 28, 100, 20).build();
		closeButton = Button.builder(Component.translatable("gui.birdwatch.close"), b -> this.onClose())
			.bounds(this.width / 2 + 10, this.height - 28, 100, 20).build();
		printButton.visible = true;
		closeButton.visible = true;
		this.addRenderableWidget(printButton);
		this.addRenderableWidget(closeButton);
	}

	/** 照片显示区域(保持 16:9,自适应窗口) */
	private int[] imageRect() {
		int maxW = this.width - 120;
		int maxH = this.height - 90;
		int imgW = maxW;
		int imgH = maxW * 9 / 16;
		if (imgH > maxH) {
			imgH = maxH;
			imgW = maxH * 16 / 9;
		}
		return new int[]{this.width / 2 - imgW / 2, 34, imgW, imgH};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		Minecraft mc = Minecraft.getInstance();
		Component title = this.getTitle();
		graphics.text(mc.font, title, this.width / 2 - mc.font.width(title) / 2, 8, 0xFFFFFFFF);

		int[] r = imageRect();
		int x = r[0], y = r[1], w = r[2], h = r[3];
		if (textureId != null) {
			graphics.blit(textureId, x, y, x + w, y + h, 0.0f, 1.0f, 0.0f, 1.0f);
		}
		// 裁剪框:暗化框外 + 亮框 + 四角柄
		int cx = (int) (x + cropX * w);
		int cy = (int) (y + cropY * h);
		int cw = (int) (cropW * w);
		int ch = (int) (cropH * h);
		graphics.fill(x, y, x + w, cy, 0x88000000);
		graphics.fill(x, cy + ch, x + w, y + h, 0x88000000);
		graphics.fill(x, cy, cx, cy + ch, 0x88000000);
		graphics.fill(cx + cw, cy, x + w, cy + ch, 0x88000000);
		graphics.outline(cx - 1, cy - 1, cw + 2, ch + 2, 0xFFFFFFFF);
		for (int[] corner : corners(cx, cy, cw, ch)) {
			graphics.fill(corner[0], corner[1], corner[0] + HANDLE, corner[1] + HANDLE, 0xFFFFFFFF);
		}
		graphics.text(mc.font, Component.translatable("screen.birdwatch.print.hint"), x, y + h + 8, 0xFFCCCCCC);
		graphics.text(mc.font, Component.translatable("screen.birdwatch.print.consume"), x, y + h + 18, 0xFF888888);
	}

	private int[][] corners(int cx, int cy, int cw, int ch) {
		return new int[][]{
			{cx - HANDLE / 2, cy - HANDLE / 2},
			{cx + cw - HANDLE / 2, cy - HANDLE / 2},
			{cx - HANDLE / 2, cy + ch - HANDLE / 2},
			{cx + cw - HANDLE / 2, cy + ch - HANDLE / 2}
		};
	}

	// ------------------------------------------------------------------
	// 拖拽裁剪框
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
		if (super.mouseClicked(event, bl)) {
			return true;
		}
		if (event.button() != 0) {
			return false;
		}
		int[] r = imageRect();
		int cx = (int) (r[0] + cropX * r[2]);
		int cy = (int) (r[1] + cropY * r[3]);
		int cw = (int) (cropW * r[2]);
		int ch = (int) (cropH * r[3]);
		int[][] corners = corners(cx, cy, cw, ch);
		double mx = event.x();
		double my = event.y();
		for (int i = 0; i < 4; i++) {
			int[] c = corners[i];
			if (mx >= c[0] && mx < c[0] + HANDLE && my >= c[1] && my < c[1] + HANDLE) {
				dragCorner = i;
				return true;
			}
		}
		// 点击框内:整体移动
		if (mx >= cx && mx < cx + cw && my >= cy && my < cy + ch) {
			dragCorner = 4;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
		if (event.button() != 0 || dragCorner < 0) {
			return super.mouseDragged(event, dx, dy);
		}
		int[] r = imageRect();
		double rx = (event.x() - r[0]) / r[2];
		double ry = (event.y() - r[1]) / r[3];
		double min = (double) BORDER / r[2];
		double minH = (double) BORDER / r[3];
		if (dragCorner == 0) { // 左上:收缩/扩张
			cropX = Math.max(0, Math.min(cropX, rx - min));
			cropW = Math.max(min, cropX + cropW - cropX);
			cropX = Math.min(cropX, 1 - cropW);
		} else if (dragCorner == 1) { // 右上
			cropW = Math.max(min, Math.min(1 - cropX, rx - cropX));
		} else if (dragCorner == 2) { // 左下
			cropH = Math.max(minH, Math.min(1 - cropY, ry - cropY));
		} else if (dragCorner == 3) { // 右下
			cropW = Math.max(min, Math.min(1 - cropX, rx - cropX));
			cropH = Math.max(minH, Math.min(1 - cropY, ry - cropY));
		} else { // 移动(用 dx/dy 增量)
			double nx = Math.max(0, Math.min(1 - cropW, cropX + dx / r[2]));
			double ny = Math.max(0, Math.min(1 - cropH, cropY + dy / r[3]));
			cropX = nx;
			cropY = ny;
		}
		return true;
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
		dragCorner = -1;
		return super.mouseReleased(event);
	}

	// ------------------------------------------------------------------
	// 印刷
	// ------------------------------------------------------------------

	/** 确认印刷:消耗 1 张纸,产出印刷照片物品(若背包无纸则提示) */
	private void doPrint() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		int paperSlot = -1;
		for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
			if (mc.player.getInventory().getItem(i).is(Items.PAPER)) {
				paperSlot = i;
				break;
			}
		}
		if (paperSlot < 0) {
			mc.player.sendSystemMessage(Component.translatable("screen.birdwatch.print.no_paper"));
			return;
		}
		// 主体鸟 id(无鸟照片仍可印刷,贴入图鉴时因无物种不可用)
		final String species;
		final int score;
		final String tier;
		var birds = record.data().get("birds");
		if (birds instanceof java.util.List<?> list && !list.isEmpty()
			&& list.get(0) instanceof java.util.Map<?, ?> m) {
			species = String.valueOf(m.get("species"));
			score = m.get("score") instanceof Number n ? n.intValue() : 0;
			Object t = m.get("tier");
			tier = t != null ? String.valueOf(t) : "";
		} else {
			species = "";
			score = 0;
			tier = "";
		}
		// 裁剪 + 缩放 512px + PNG 编码,字节上传服务端(印刷图存服务端存档,与物品绑定)
		final double[] c = parseCrop(cropX + "," + cropY + "," + cropW + "," + cropH);
		final byte[] pngBytes = cropAndEncode(c);
		if (pngBytes == null) {
			mc.player.sendSystemMessage(Component.translatable("screen.birdwatch.print.fail"));
			return;
		}

		// 印刷请求走服务端:服务端写印刷图文件、消耗纸、创建物品入包
		// (客户端直接 add 到本地背包是幽灵物品,生存模式点击即被同步清掉)
		String birdsJson = new com.google.gson.Gson().toJson(birds);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
			new com.birdwatch.network.ModNetworking.PrintRequestPayload(
				pngBytes, species, score, tier, "0,0,1,1", birdsJson)); // 裁剪已烘焙进文件
		BirdWatchMod.LOGGER.info("[Print] 印刷请求已发送 bytes={} species={}", pngBytes.length, species);
		mc.player.sendSystemMessage(Component.translatable("screen.birdwatch.print.done"));
		this.onClose();
	}

	/**
	 * 按当前裁剪矩形裁切原图 → 缩放(最大边 512px)→ PNG 编码。
	 * 返回 PNG 字节;失败返回 null。
	 */
	private byte[] cropAndEncode(double[] c) {
		try {
			NativeImage source;
			try (InputStream in = Files.newInputStream(record.pngPath())) {
				source = NativeImage.read(in);
			}
			int w = Math.max(1, (int) Math.round(source.getWidth() * c[2]));
			int h = Math.max(1, (int) Math.round(source.getHeight() * c[3]));
			int sx = (int) Math.round(source.getWidth() * c[0]);
			int sy = (int) Math.round(source.getHeight() * c[1]);
			NativeImage cropped = new NativeImage(w, h, true);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					cropped.setPixel(x, y, source.getPixel(Math.min(source.getWidth() - 1, sx + x),
						Math.min(source.getHeight() - 1, sy + y)));
				}
			}
			source.close();
			// 缩放最大边 512px(等比),控制网络包大小;印刷纹理展示本就小,清晰度足够
			NativeImage scaled = scaleMaxSide(cropped, 512);
			cropped.close();
			// 26.2 NativeImage 无 writeToBytes:临时文件中转 → 读字节 → 删除
			Path tmp = Files.createTempFile("birdwatch_print", ".png");
			try {
				scaled.writeToFile(tmp);
				return Files.readAllBytes(tmp);
			} finally {
				scaled.close();
				Files.deleteIfExists(tmp);
			}
		} catch (IOException e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 裁剪照片编码失败", e);
			return null;
		}
	}

	/**
	 * 等比缩放到最大边 ≤ maxSide;已满足则返回独立拷贝(不能返回原对象 ——
	 * 调用方会 close 原图,返回同一对象会导致 writeToFile 抛
	 * "Image is not allocated"(实测踩坑)。
	 */
	private static NativeImage scaleMaxSide(NativeImage img, int maxSide) {
		int w = img.getWidth();
		int h = img.getHeight();
		if (w <= maxSide && h <= maxSide) {
			NativeImage copy = new NativeImage(w, h, true);
			copy.copyFrom(img);
			return copy;
		}
		double scale = (double) maxSide / Math.max(w, h);
		int nw = Math.max(1, (int) Math.round(w * scale));
		int nh = Math.max(1, (int) Math.round(h * scale));
		NativeImage out = new NativeImage(nw, nh, true);
		for (int y = 0; y < nh; y++) {
			for (int x = 0; x < nw; x++) {
				out.setPixel(x, y, img.getPixel(Math.min(w - 1, (int) (x / scale)),
					Math.min(h - 1, (int) (y / scale))));
			}
		}
		img.close();
		return out;
	}

	private static double[] parseCrop(String cropStr) {
		String[] parts = cropStr.split(",");
		if (parts.length == 4) {
			try {
				return new double[]{
					Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
					Double.parseDouble(parts[2]), Double.parseDouble(parts[3])
				};
			} catch (NumberFormatException ignored) {
			}
		}
		return new double[]{0, 0, 1, 1};
	}
}
