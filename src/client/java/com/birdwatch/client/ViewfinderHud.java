package com.birdwatch.client;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.camera.LensDefinition;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 取景器界面(EVF 风格):
 * - 四周黑色取景器边框 + 顶部/底部信息条 —— "进入另一个界面"的观感
 * - 中央对焦框 + 目标距离
 * - 底部相机风格参数条(快门/光圈/ISO/EV)
 * - 曝光条、景深范围、手持不稳警告
 * - 快门白闪反馈
 */
public final class ViewfinderHud implements HudElement {
	private static final int COLOR_NORMAL = 0xFFFFFFFF;
	private static final int COLOR_SELECTED = 0xFFFFFF55;
	private static final int COLOR_FOCUS_OK = 0xFF55FF55;
	private static final int COLOR_FOCUS_BAD = 0xFFFFFFFF;
	private static final int COLOR_WARN = 0xFFFF5555;
	private static final int COLOR_HINT = 0xFFAAAAAA;
	private static final int BLACK = 0xFF000000;
	/** 左右边框厚度 */
	private static final int SIDE = 16;
	/** 顶部/底部黑色区域总高(边框+条,减约 1/3) */
	private static final int TOP_UI = 15;
	private static final int BOTTOM_UI = 15;
	/** 直方图尺寸(绘制于画面区域左下角) */
	private static final int HIST_W = 110;
	private static final int HIST_H = 44;

	public static void register() {
		HudElementRegistry.addFirst(
			Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "viewfinder_hud"),
			new ViewfinderHud());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		CameraSession session = CameraSession.get();
		if (session == null || !session.isActive()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		int w = g.guiWidth();
		int h = g.guiHeight();

		// ---- 黑色边框(取景器外壳) ----
		g.fill(0, 0, w, TOP_UI, BLACK);
		g.fill(0, h - BOTTOM_UI, w, h, BLACK);
		g.fill(0, TOP_UI, SIDE, h - BOTTOM_UI, BLACK);
		g.fill(w - SIDE, TOP_UI, w, h - BOTTOM_UI, BLACK);

		// ---- 顶部信息:镜头信息(左)+ 模式/ISO(右)+ 提示(中央),垂直居中于黑色区域 ----
		LensDefinition lens = session.getLens();
		String lensText = (lens != null ? Component.translatable(lens.nameKey()).getString() : "?")
			+ (lens != null && lens.type() == LensDefinition.LensType.ZOOM ? "  " + session.getFocalLength() + "mm" : "")
			+ String.format("  ≈%.1fx", session.magnification());
		g.text(font, lensText, SIDE + 4, (TOP_UI - 9) / 2, COLOR_NORMAL);
		String modeText = "M  ISO " + session.getIso();
		g.text(font, modeText, w - SIDE - 4 - font.width(modeText), (TOP_UI - 9) / 2, COLOR_NORMAL);

		// ---- 参数行(数字键选中项高亮) ----
		int paramY = TOP_UI + 4;
		String[] lines = session.hudLines();
		for (int i = 0; i < lines.length; i++) {
			int color = session.getSelectedParam() == i ? COLOR_SELECTED : COLOR_NORMAL;
			g.text(font, lines[i], SIDE + 4, paramY, color);
			paramY += 12;
		}

		// ---- 中央对焦框 + 距离 ----
		int half = 26;
		int cx = w / 2;
		int cy = h / 2;
		int bracketColor = session.isInFocus() ? COLOR_FOCUS_OK : COLOR_FOCUS_BAD;
		int arm = 8;
		g.horizontalLine(cx - half, cx - half + arm, cy - half, bracketColor);
		g.horizontalLine(cx + half - arm, cx + half, cy - half, bracketColor);
		g.horizontalLine(cx - half, cx - half + arm, cy + half, bracketColor);
		g.horizontalLine(cx + half - arm, cx + half, cy + half, bracketColor);
		g.verticalLine(cx - half, cy - half, cy - half + arm, bracketColor);
		g.verticalLine(cx + half, cy - half, cy - half + arm, bracketColor);
		g.verticalLine(cx - half, cy + half - arm, cy + half, bracketColor);
		g.verticalLine(cx + half, cy + half - arm, cy + half, bracketColor);

		if (session.getTargetDistance() >= 0) {
			String distText = String.format("目标 %.1fm", session.getTargetDistance());
			g.text(font, distText, cx - font.width(distText) / 2, cy + half + 4, bracketColor);
		}

		// ---- 底部信息条:相机风格参数(一行,垂直居中) ----
		int infoY = h - BOTTOM_UI;
		g.fill(0, infoY, w, h, BLACK);
		String readout = session.shutterText() + "  F" + session.getAperture()
			+ "  ISO " + session.getIso()
			+ String.format("  %+.1fEV", session.exposureStops());
		g.text(font, readout, SIDE + 8, infoY + (BOTTOM_UI - 9) / 2, COLOR_NORMAL);
		String focusText = String.format("对焦 %.1fm", session.getFocusDistance());
		g.text(font, focusText, w - SIDE - 8 - font.width(focusText), infoY + (BOTTOM_UI - 9) / 2,
			session.isInFocus() ? COLOR_FOCUS_OK : COLOR_NORMAL);

		// ---- 直方图(画面区域左下角),景深/EV 条堆叠其上 ----
		int histX = SIDE + 4;
		int histY = h - BOTTOM_UI - HIST_H - 2;
		drawHistogram(g, histX, histY, HIST_W, HIST_H);
		int leftX = SIDE + 4;
		int leftY = histY - 22;
		drawExposure(g, font, leftX, leftY);
		double[] dof = session.dofRange();
		if (dof != null) {
			String text = dof[1] < 0
				? String.format("景深 %.1fm~∞", dof[0])
				: String.format("景深 %.1fm~%.1fm", dof[0], dof[1]);
			g.text(font, text, leftX, histY - 11, COLOR_HINT);
		}
		// 快门过慢警告:图像预览底部、直方图右侧
		if (session.shakeWarning()) {
			String shake = Component.translatable("hud.birdwatch.shake").getString();
			g.text(font, shake, leftX + HIST_W + 8, histY + HIST_H - 10, COLOR_WARN);
		}

		// ---- 顶部提示(垂直居中于黑色区域) ----
		Component hint = Component.translatable("hud.birdwatch.hint");
		g.text(font, hint, w / 2 - font.width(hint) / 2, (TOP_UI - 9) / 2, COLOR_HINT);
		if (session.getLens() == null) {
			Component noLens = Component.translatable("hud.birdwatch.no_lens");
			g.text(font, noLens, w / 2 - font.width(noLens) / 2, TOP_UI + 4, COLOR_WARN);
		}

		// ---- 曝光预览:过曝泛白 / 欠曝变暗(仅叠加画面区域,不污染黑色边框) ----
		float overlayAlpha = session.exposureOverlayAlpha();
		if (overlayAlpha > 0) {
			int alpha = (int) (overlayAlpha * 255);
			g.fill(SIDE, TOP_UI, w - SIDE, h - BOTTOM_UI,
				session.isOverexposed() ? ((alpha << 24) | 0xFFFFFF) : (alpha << 24));
		}

		// ---- 快门白闪 ----
		int flashTicks = session.getCaptureFlashTicks();
		if (flashTicks > 0) {
			int alpha = (int) (0x50 * (flashTicks / 3.0f));
			g.fill(0, 0, w, h, (alpha << 24) | 0xFFFFFF);
		}
	}

	/** 亮度直方图:256 桶压缩为 N 根竖条,暗部/亮部裁剪区标红;黑色半透明背景 */
	private void drawHistogram(GuiGraphicsExtractor g, int x, int y, int width, int height) {
		g.fill(x - 4, y - 3, x + width + 4, y + height + 3, 0x99000000);
		int[] hist = CameraSession.get().getHistogram();
		int bars = Math.min(64, width / 2);
		int max = 1;
		for (int i = 0; i < 256; i++) {
			if (hist[i] > max) max = hist[i];
		}
		int barWidth = width / bars;
		for (int i = 0; i < bars; i++) {
			int count = 0;
			for (int b = 0; b < 256 / bars; b++) {
				count += hist[i * (256 / bars) + b];
			}
			// 条高防御:不超过背景高度,防止超出半透明背景
			int barHeight = Math.max(1, Math.min(height - 1, (int) (height * Math.log1p(count) / Math.log1p(max))));
			int color = COLOR_NORMAL;
			if (i < 2 || i >= bars - 2) {
				color = COLOR_WARN; // 阴影/高光裁剪区
			}
			g.fill(x + i * barWidth, y + height - barHeight, x + (i + 1) * barWidth - 1, y + height, color);
		}
	}

	/** 曝光条:◀ 欠曝 ---- 正常 ---- 过曝 ▶,游标随 EV 偏移 */
	private void drawExposure(GuiGraphicsExtractor g, Font font, int x, int y) {
		float ev = CameraSession.get().exposureStops();
		String bar = "◀◀----|----▶▶";
		int pos = (int) Math.round(clamp(ev, -3, 3) / 3.0 * 4.0) + 4;
		int color = Math.abs(ev) <= 1 ? COLOR_FOCUS_OK : (Math.abs(ev) <= 2 ? COLOR_SELECTED : COLOR_WARN);
		StringBuilder sb = new StringBuilder("曝光 ");
		for (int i = 0; i < bar.length(); i++) {
			sb.append(i == pos ? '▮' : bar.charAt(i));
		}
		g.text(font, sb.toString(), x, y, color);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
