package com.birdwatch.client;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.camera.LensDefinition;
import com.birdwatch.camera.LensRegistry;
import com.birdwatch.item.CameraItem;
import com.birdwatch.registry.ModItems;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * 取景器会话(M1):
 * - 右键进入/拍照,潜行+右键打开镜头选择,ESC 退出(KeyboardHandlerMixin 拦截,不触发暂停)
 * - 数字键 1-5 选择调节项(KeyboardHandlerMixin 拦截,与物品栏隔离),滚轮调值
 * - 中心射线测距 + 曝光/景深/防抖计算(参数的可视反馈,M3 做真实景深渲染)
 */
public final class CameraSession {
	public static final int PARAM_FOCAL = 0;
	public static final int PARAM_APERTURE = 1;
	public static final int PARAM_SHUTTER = 2;
	public static final int PARAM_ISO = 3;
	public static final int PARAM_FOCUS = 4;
	private static final int PARAM_COUNT = 5;

	/** 快门档位(秒) */
	public static final double[] SHUTTERS = {1 / 15.0, 1 / 30.0, 1 / 60.0, 1 / 125.0, 1 / 250.0, 1 / 500.0, 1 / 1000.0, 1 / 2000.0};
	/** ISO 档位 */
	public static final int[] ISOS = {100, 200, 400, 800, 1600, 3200, 6400, 12800};
	private static final double MIN_FOCUS = 2.0;
	private static final double MAX_FOCUS = 200.0;
	/** 基准视角:24mm = 1x 放大倍率(用户约定) */
	private static final double BASE_FOCAL = 24.0;
	private static final double BASE_FOV = 70.0;
	/** 直方图读回间隔(tick) */
	private static final int HISTOGRAM_INTERVAL = 5;
	/** 弥散圆(35mm 全画幅,mm) */
	private static final double CIRCLE_OF_CONFUSION = 0.03;

	private static CameraSession instance;

	private final Minecraft minecraft;

	private boolean active;
	private LensDefinition lens;
	private int focalLength;
	private float aperture;
	private int shutterIndex = 2;
	private int isoIndex = 0;
	private double focusDistance = 20.0;
	private int selectedParam = PARAM_FOCUS;
	private boolean prevRightPressed;
	private boolean captureQueued;
	private double scrollAccumulator;
	private double lastTargetDistance = -1;
	private int captureFlashTicks;
	private int histogramTimer;
	/** 亮度直方图(256 桶,由帧缓冲读回计算) */
	private final int[] histogram = new int[256];
	private final boolean[] prevParamKeyDown = new boolean[PARAM_COUNT];
	/** 参数键的 GLFW 键码 */
	private static final int[] PARAM_KEYS = {
		GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_5
	};

	private CameraSession(Minecraft minecraft) {
		this.minecraft = minecraft;
	}

	public static void init() {
		instance = new CameraSession(Minecraft.getInstance());
		ClientTickEvents.END_CLIENT_TICK.register(instance::tick);
		ClientHotbarScrollEvents.ALLOW.register(instance::onScroll);
		LevelRenderEvents.END_MAIN.register(context -> instance.onLevelRenderEnd());
		BirdWatchMod.LOGGER.info("[BirdWatch] 取景器会话已初始化");
	}

	public static CameraSession get() {
		return instance;
	}

	public static boolean isViewfinderActive() {
		return instance != null && instance.active;
	}

	public LensDefinition getLens() {
		return lens;
	}

	public int getFocalLength() {
		return focalLength;
	}

	public float getAperture() {
		return aperture;
	}

	public String shutterText() {
		return "1/" + shutterString(SHUTTERS[shutterIndex]);
	}

	public double getShutter() {
		return SHUTTERS[shutterIndex];
	}

	public int getIso() {
		return ISOS[isoIndex];
	}

	public double getFocusDistance() {
		return focusDistance;
	}

	public double getTargetDistance() {
		return lastTargetDistance;
	}

	/** 参数键轮询(取景器内):数字键 1-5 边缘触发切换参数 */
	private void pollParamKeys(Minecraft mc) {
		for (int i = 0; i < PARAM_COUNT; i++) {
			boolean down = InputConstants.isKeyDown(mc.getWindow(), PARAM_KEYS[i]);
			if (down && !prevParamKeyDown[i]) {
				selectedParam = i;
			}
			prevParamKeyDown[i] = down;
		}
	}

	private boolean mouseGrabbed = true;
	private boolean prevInventoryDown;
	/** 相册关闭后是否自动恢复取景器(由 E 键打开相册时置位) */
	private static boolean resumeViewfinderAfterAlbum;

	private void tick(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		// 有界面打开时(鼠标未被捕获)不处理取景器输入
		if (!mc.mouseHandler.isMouseGrabbed()) {
			prevRightPressed = false;
			prevInventoryDown = false;
			mouseGrabbed = false;
			return;
		}
		if (!mouseGrabbed) {
			// 屏幕刚关闭(鼠标重新抓取):以当前右键状态为基线,吞掉关屏瞬间的残留按下,
			// 否则 ESC 关闭相册后同一右键会被判为边缘触发,立即重开界面
			prevRightPressed = mc.mouseHandler.isRightPressed();
			prevInventoryDown = InputConstants.isKeyDown(mc.getWindow(),
				KeyMappingHelper.getBoundKeyOf(mc.options.keyInventory).getValue());
			mouseGrabbed = true;
			return;
		}

		boolean rightPressed = mc.mouseHandler.isRightPressed();
		boolean shiftDown = mc.player.isShiftKeyDown();
		ItemStack held = mc.player.getMainHandItem();
		boolean holdingCamera = held.is(ModItems.CAMERA);
		boolean holdingHandbook = held.is(ModItems.HANDBOOK);
		boolean holdingPhotoPrint = held.is(ModItems.PHOTO_PRINT);

		if (!active) {
			if (rightPressed && !prevRightPressed) {
				if (holdingHandbook) {
					// 观鸟图鉴:右键打开图鉴界面(纯客户端本地进度)
					openHandbook();
				} else if (holdingPhotoPrint) {
					// 印刷照片:右键预览(裁剪后的照片大图)。
					// 但若准星瞄准了可交互目标(展示框/实体/方块),不抢交互,
					// 让原版 useOn 生效(如放入展示框)。
					if (mc.hitResult == null || mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
						Minecraft.getInstance().setScreenAndShow(
							new com.birdwatch.client.handbook.PhotoPreviewScreen(held));
					}
				} else if (holdingCamera && !shiftDown) {
					enterViewfinder();
				} else if (holdingCamera) {
					// 潜行+右键:客户端判定潜行(服务端 isShiftKeyDown 不可靠),C2S 请求打开镜头槽
					ClientPlayNetworking.send(new com.birdwatch.network.ModNetworking.OpenLensMenuPayload());
				}
			}
		} else {
			// 取景器内 E(背包键):原始按键轮询边缘触发,退出取景器并打开相册。
			// 不用 consumeClick —— 原版 handleKeybinds 在 END_CLIENT_TICK 之前消费 click,
			// 会先打开背包;E 的 KeyMapping 状态已被 KeyMappingMixin 拦截,背包不会开。
			InputConstants.Key invKey = KeyMappingHelper.getBoundKeyOf(mc.options.keyInventory);
			boolean invDown = InputConstants.isKeyDown(mc.getWindow(), invKey.getValue());
			if (invDown && !prevInventoryDown) {
				exitViewfinder();
				openAlbum(true); // 相册关闭后恢复取景器
			}
			prevInventoryDown = invDown;
			if (rightPressed && !prevRightPressed) {
				if (shiftDown) {
					// 取景器内潜行+右键:退出取景器并打开镜头槽
					exitViewfinder();
					ClientPlayNetworking.send(new com.birdwatch.network.ModNetworking.OpenLensMenuPayload());
				} else {
					queueCapture();
				}
			}
		}
		prevRightPressed = rightPressed;

		if (active) {
			updateTargetDistance();
			pollParamKeys(mc);
		}
		if (captureFlashTicks > 0) {
			captureFlashTicks--;
		}
	}

	private boolean onScroll(net.minecraft.world.entity.player.Inventory inventory, int a, int b, double x, double y) {
		if (!active) {
			return true;
		}
		applyScrollDelta(y);
		return false; // 消费滚轮,阻止切换快捷栏
	}

	/** 打开相册;resume=true 时关闭相册后自动恢复取景器 */
	public static void openAlbum(boolean resumeViewfinder) {
		resumeViewfinderAfterAlbum = resumeViewfinder;
		Minecraft.getInstance().setScreenAndShow(new AlbumScreen());
	}

	/** 打开观鸟图鉴(右键手册;M2a 本地进度) */
	public static void openHandbook() {
		com.birdwatch.client.handbook.HandbookProgress.load();
		Minecraft.getInstance().setScreenAndShow(new com.birdwatch.client.handbook.HandbookScreen());
	}

	/** 相册关闭后是否恢复取景器 */
	public static boolean shouldResumeViewfinderAfterAlbum() {
		return resumeViewfinderAfterAlbum;
	}

	public static void clearResumeViewfinder() {
		resumeViewfinderAfterAlbum = false;
	}

	/** 由印刷页返回相册时恢复其取景器恢复状态(印刷打开前相册的状态) */
	public static void setResumeViewfinderAfterAlbum(boolean value) {
		resumeViewfinderAfterAlbum = value;
	}

	public void enterViewfinder() {
		ItemStack held = minecraft.player.getMainHandItem();
		LensDefinition def = LensRegistry.byId(CameraItem.getLensId(held));
		setLens(def);
		setActive(true);
	}

	public void setLens(LensDefinition def) {
		lens = def;
		if (lens == null) {
			return; // 无镜头状态:不跑 DoF、禁止拍照
		}
		// 镜头装上后收敛参数到镜头能力范围
		if (focalLength < def.minFocal() || focalLength > def.maxFocal()) {
			focalLength = def.minFocal();
		}
		aperture = clampToStops(aperture == 0 ? def.maxAperture() : aperture, def);
	}

	public void setActive(boolean value) {
		active = value;
		if (!value) {
			captureQueued = false;
		}
		BirdWatchMod.LOGGER.info("[BirdWatch] 取景器 {}", value ? "开启" : "关闭");
	}

	public void exitViewfinder() {
		setActive(false);
	}

	private void queueCapture() {
		if (lens == null) {
			if (minecraft.player != null) {
				minecraft.player.sendSystemMessage(Component.translatable("hud.birdwatch.no_lens"));
			}
			return;
		}
		captureQueued = true;
		captureFlashTicks = 3;
		// 快门音效(暂用原版按钮点击音替代,自制素材就绪后替换)
		AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
		if (minecraft.player != null) {
			minecraft.player.sendSystemMessage(Component.translatable("hud.birdwatch.shutter"));
		}
	}

	private void onLevelRenderEnd() {
		// 链 A:读主目标(上一帧完整颜色+深度)做景深,输出到私有目标
		processDof();
		if (captureQueued) {
			captureQueued = false;
			PhotoData data = snapshot();
			BirdWatchMod.LOGGER.info("[BirdWatch] 拍照:{}mm F{} 1/{} ISO{} 对焦{}m", data.focalLength, data.aperture,
				shutterString(data.shutter), data.iso, (int) data.focusDistance);
			// M2a:拍摄判定纯客户端,向服务端授奖(重复触发由服务端成就系统过滤)
			for (com.birdwatch.client.photo.ScoredBird bird : data.birds()) {
				if (bird.qualifies()) {
					ClientPlayNetworking.send(new com.birdwatch.network.ModNetworking.PhotoRatedPayload(
						bird.speciesId(), bird.score()));
				}
			}
			// 照片源 = dof_target(虚化处理后的画面本体,不含 UI)
			if (dofTarget != null) {
				Screenshot.takeScreenshot(dofTarget, image -> PhotoSaver.save(image, data));
			} else {
				Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(),
					image -> PhotoSaver.save(image, data));
			}
		}
		// 直方图周期读回(取景器内)
		if (active && --histogramTimer <= 0) {
			histogramTimer = HISTOGRAM_INTERVAL;
			Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> {
				computeHistogram(image);
				image.close();
			});
		}
	}

	/**
	 * 从帧缓冲计算亮度直方图(步进采样,控制 CPU 成本)。
	 * 应用与屏幕叠加一致的曝光变换 —— 直方图反映"当前看到的画面"。
	 */
	private void computeHistogram(com.mojang.blaze3d.platform.NativeImage image) {
		java.util.Arrays.fill(histogram, 0);
		float alpha = exposureOverlayAlpha();
		boolean overexposed = exposureStops() > 0;
		int step = 4;
		for (int y = 0; y < image.getHeight(); y += step) {
			for (int x = 0; x < image.getWidth(); x += step) {
				int argb = image.getPixel(x, y);
				int r = (argb >> 16) & 0xFF;
				int g = (argb >> 8) & 0xFF;
				int b = argb & 0xFF;
				int lum = (int) (0.2126 * r + 0.7152 * g + 0.0722 * b);
				if (alpha > 0) {
					lum = overexposed
						? (int) (lum * (1 - alpha) + 255 * alpha)
						: (int) (lum * (1 - alpha));
				}
				histogram[lum & 0xFF]++;
			}
		}
	}

	/** 曝光预览叠加强度(与 HUD 一致):|EV|<=0.5 无叠加;极端组合(>14EV)不叠加,只靠数值/直方图提示 */
	public float exposureOverlayAlpha() {
		float ev = exposureStops();
		if (Math.abs(ev) <= 0.5f || Math.abs(ev) > 14f) {
			return 0;
		}
		return (float) clamp((Math.abs(ev) - 0.5f) * 0.10, 0.0, 0.32);
	}

	public boolean isOverexposed() {
		return exposureStops() > 0;
	}

	public int[] getHistogram() {
		return histogram;
	}

	private void applyScrollDelta(double delta) {
		scrollAccumulator += delta;
		int steps = (int) scrollAccumulator;
		if (steps == 0) {
			return;
		}
		scrollAccumulator -= steps;
		int dir = steps > 0 ? 1 : -1;
		steps = Math.abs(steps);
		for (int i = 0; i < steps; i++) {
			adjustParam(dir);
		}
	}

	private void adjustParam(int dir) {
		switch (selectedParam) {
			case PARAM_FOCAL -> {
				if (lens != null && lens.type() == LensDefinition.LensType.ZOOM) {
					focalLength = clamp(focalLength + dir * 10, lens.minFocal(), lens.maxFocal());
					dofConfigDirty = true; // 焦距变化影响虚化(当前仅影响对焦换算,预留)
				}
			}
			case PARAM_APERTURE -> {
				if (lens != null) {
					float[] stops = LensRegistry.apertureStops(lens);
					int idx = nearestStopIndex(aperture, stops) + dir;
					idx = clamp(idx, 0, stops.length - 1);
					if (aperture != stops[idx]) {
						aperture = stops[idx];
						dofConfigDirty = true;
					}
				}
			}
			case PARAM_SHUTTER -> shutterIndex = clamp(shutterIndex + dir, 0, SHUTTERS.length - 1);
			case PARAM_ISO -> isoIndex = clamp(isoIndex + dir, 0, ISOS.length - 1);
			case PARAM_FOCUS -> {
				double step = Math.max(0.5, focusDistance * 0.05);
				double next = clamp(focusDistance + dir * step, MIN_FOCUS, MAX_FOCUS);
				if (next != focusDistance) {
					focusDistance = next;
					dofConfigDirty = true;
				}
			}
			default -> {
			}
		}
	}

	/** 中心射线测距:目标距离(对焦参考),M2 将用于评分 */
	private void updateTargetDistance() {
		Camera camera = minecraft.gameRenderer.mainCamera();
		Vec3 start = camera.position();
		Vector3f forward = camera.rotation().transform(new Vector3f(0, 0, -1));
		Vec3 end = start.add(new Vec3(forward.x, forward.y, forward.z).scale(MAX_FOCUS));
		BlockHitResult hit = minecraft.level.clipIncludingBorder(new ClipContext(
			start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
		if (hit.getType() == HitResult.Type.MISS) {
			lastTargetDistance = MAX_FOCUS;
		} else {
			lastTargetDistance = start.distanceTo(hit.getLocation());
		}
	}

	/** FOV 覆盖(CameraMixin 调用);未激活时返回 -1 表示不干预 */
	public float computeFov() {
		if (!active || lens == null) {
			return -1;
		}
		// 24mm = 1x:倍率 = 焦距/24,视场角随倍率线性缩小
		double fov = 2 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(BASE_FOV / 2)) * (BASE_FOCAL / focalLength)));
		return (float) clamp(fov, 5, 120);
	}

	/** 当前放大倍率(24mm = 1x) */
	public double magnification() {
		return focalLength / BASE_FOCAL;
	}

	// ---- 参数的可视反馈(M1:M3 做真实景深渲染) ----

	/**
	 * 曝光指示(标准 EV 方程):light + log2(ISO/100) − 2·log2(N) + log2(t)。
	 * >0 过曝,<0 欠曝,|x|<=1 正常;阳光十六法则(F16/1/125/ISO100/晴天 15)≈ 0。
	 * 大光圈(小 F 数)、慢快门、高 ISO、亮环境 → 数值增大(过曝方向)。
	 * M2 评分将复用此模型。
	 */
	public float exposureStops() {
		int light = sampleLightLevel();
		return (float) (light
			+ Math.log(ISOS[isoIndex] / 100.0) / Math.log(2)
			- 2 * Math.log(aperture) / Math.log(2)
			+ Math.log(SHUTTERS[shutterIndex]) / Math.log(2));
	}

	/**
	 * 执行景深后处理链(v1:中心自动对焦 + 光圈强度;焦点距离联动在 v1.1)。
	 * 失败仅记录一次,不影响游戏。
	 */
	/** DoF 私有目标 ID(链 A 输出) */
	private static final Identifier DOF_TARGET_ID = Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "dof_target");
	private boolean dofErrorLogged;
	private net.minecraft.client.renderer.PostChain dofChain;
	private com.mojang.blaze3d.pipeline.RenderTarget dofTarget;
	/** 参数变化后需要重建链(对焦/光圈编译进 uniform) */
	private boolean dofConfigDirty = true;
	/** 上次重建时的曝光值(节流:变化 >0.5EV 才重建) */
	private double lastRebuildEv = Double.NaN;

	/**
	 * 链 A(END_MAIN 执行):读主目标(上一帧完整颜色+深度)做景深虚化,输出到私有目标。
	 * 26.2 渲染管线在 HUD 前不写主目标深度,故必须在帧末读取。
	 */
	public void processDof() {
		if (!active || lens == null) {
			return;
		}
		BirdWatchMod.LOGGER.info("[BirdWatch] 链A执行(DoF→dof_target)");
		try {
			ensureDofTarget();
			double ev = exposureStops();
			boolean evChanged = Double.isNaN(lastRebuildEv) || Math.abs(ev - lastRebuildEv) > 0.5;
			if (dofConfigDirty || dofChain == null || evChanged) {
				rebuildDofChain();
				lastRebuildEv = ev;
			}
			runChain(dofChain);
		} catch (Exception e) {
			if (!dofErrorLogged) {
				dofErrorLogged = true;
				BirdWatchMod.LOGGER.error("[BirdWatch] DoF 后处理失败(已禁用本会话)", e);
			}
		}
	}

	/** 拷贝(帧末执行,由 GameRendererMixin 调用):把虚化结果从私有目标拷回主目标;返回是否执行了拷贝 */
	public boolean blitDofToMain() {
		if (!active || dofTarget == null || lens == null) {
			return false;
		}
		try {
			com.mojang.blaze3d.pipeline.RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
			int w = Math.min(dofTarget.width, main.width);
			int h = Math.min(dofTarget.height, main.height);
			com.mojang.blaze3d.systems.CommandEncoder encoder =
				com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
			// 全屏拷贝:虚化画面覆盖整个 main,随后 GuiRenderer.render() 重绘 UI 在最上层
			// 参数顺序:(src, dst, mip, dstX, dstY, srcX, srcY, dstW, dstH)
			encoder.copyTextureToTexture(dofTarget.getColorTexture(), main.getColorTexture(),
				0, 0, 0, 0, 0, w, h);
		} catch (Exception e) {
			if (!dofErrorLogged) {
				dofErrorLogged = true;
				BirdWatchMod.LOGGER.error("[BirdWatch] DoF 回贴失败(已禁用本会话)", e);
			}
			return false;
		}
		return true;
	}

	/** 以自定义目标包执行链:main + dof_target 双目标 */
	private void runChain(net.minecraft.client.renderer.PostChain chain) {
		com.mojang.blaze3d.pipeline.RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		com.mojang.blaze3d.framegraph.FrameGraphBuilder builder = new com.mojang.blaze3d.framegraph.FrameGraphBuilder();
		com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> mainHandle =
			builder.importExternal("main", main);
		com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> dofHandle =
			builder.importExternal("dof_target", dofTarget);
		// 注意:TargetBundle.of() 创建的 bundle 的 replace 只允许替换已有条目,
		// 添加新 id 会抛 "No target with id" —— 因此自定义实现(真正的 map)
		net.minecraft.client.renderer.PostChain.TargetBundle bundle = new net.minecraft.client.renderer.PostChain.TargetBundle() {
			private final java.util.Map<Identifier, com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget>> targets =
				new java.util.HashMap<>();

			{
				targets.put(net.minecraft.client.renderer.LevelTargetBundle.MAIN_TARGET_ID, mainHandle);
				targets.put(DOF_TARGET_ID, dofHandle);
			}

			@Override
			public void replace(Identifier id, com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> handle) {
				targets.put(id, handle);
			}

			@Override
			public com.mojang.blaze3d.resource.ResourceHandle<com.mojang.blaze3d.pipeline.RenderTarget> get(Identifier id) {
				return targets.get(id);
			}
		};
		chain.addToFrame(builder, main.width, main.height, bundle);
		builder.execute(((com.birdwatch.client.mixin.GameRendererAccessor) minecraft.gameRenderer).birdwatch$resourcePool());
	}

	private void ensureDofTarget() {
		com.mojang.blaze3d.pipeline.RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		if (dofTarget == null) {
			dofTarget = new com.mojang.blaze3d.pipeline.TextureTarget("birdwatch_dof", main.width, main.height, true, com.mojang.blaze3d.GpuFormat.RGBA8_UNORM);
		} else if (dofTarget.width != main.width || dofTarget.height != main.height) {
			dofTarget.resize(main.width, main.height);
		}
	}

	/**
	 * 重建 DoF 链:对焦距离(转 reversed-Z 深度)与光圈系数编译进 uniform。
	 * 参数变化时才重建(shader 编译 ~几十毫秒,可接受);光圈 F 越小系数越大。
	 */
	private void rebuildDofChain() {
		double near = 0.05;
		double far = 1000.0;
		double s = Math.max(focusDistance, 0.1);
		double zNdc = (far + near) / (far - near) - (2 * near * far) / ((far - near) * s);
		double zRev = (1 - zNdc) / 2;
		// 曝光增益:2^EV,过曝泛白、欠曝变暗
		float exposureScale = (float) clamp(Math.pow(2, exposureStops()), 0.05, 20.0);
		String json = """
			{
				"passes": [{
					"vertex_shader": "minecraft:core/screenquad",
					"fragment_shader": "birdwatch:post/dof",
					"inputs": [
						{ "sampler_name": "In", "target": "minecraft:main", "bilinear": true },
						{ "sampler_name": "InDepth", "target": "minecraft:main", "use_depth_buffer": true }
					],
					"output": "birdwatch:dof_target",
					"uniforms": { "DofConfig": [
						{ "name": "FocusDepthRaw", "type": "float", "value": %f },
						{ "name": "FocalMM", "type": "float", "value": %f },
						{ "name": "ApertureF", "type": "float", "value": %f },
						{ "name": "FocusDistM", "type": "float", "value": %f },
						{ "name": "Near", "type": "float", "value": 0.05 },
						{ "name": "Far", "type": "float", "value": 1000.0 },
						{ "name": "ExposureScale", "type": "float", "value": %f }
					]}
				}]
			}
			""".formatted(zRev, (float) focalLength, aperture, focusDistance, exposureScale);
		net.minecraft.client.renderer.PostChainConfig config = net.minecraft.client.renderer.PostChainConfig.CODEC
			.parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(json))
			.getOrThrow();
		try {
			net.minecraft.client.renderer.PostChain newChain = net.minecraft.client.renderer.PostChain.load(config,
				minecraft.getTextureManager(),
				java.util.Set.of(net.minecraft.client.renderer.LevelTargetBundle.MAIN_TARGET_ID, DOF_TARGET_ID),
				Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "dof"),
				((com.birdwatch.client.mixin.ShaderManagerAccessor) minecraft.getShaderManager()).birdwatch$postChainProjection(),
				((com.birdwatch.client.mixin.ShaderManagerAccessor) minecraft.getShaderManager()).birdwatch$postChainProjectionMatrixBuffer());
			if (dofChain != null) {
				dofChain.close();
			}
			dofChain = newChain;
			dofConfigDirty = false;
			BirdWatchMod.LOGGER.info("[BirdWatch] DoF 链重建:{}mm F{} 对焦{}m 曝光{:.1f}EV", focalLength, aperture, (int) focusDistance, exposureStops());
		} catch (net.minecraft.client.renderer.ShaderManager.CompilationException e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] DoF 链重建失败,保留旧链", e);
		}
	}

	/** 环境光采样:M 档下曝光由相机所在位置的环境光决定,不随瞄准目标变化 */
	private int sampleLightLevel() {
		Camera camera = minecraft.gameRenderer.mainCamera();
		return minecraft.level.getLightEngine().getRawBrightness(camera.blockPosition(), minecraft.level.getSkyDarken());
	}

	/** 景深范围(米):[近,远],far 为 -1 表示无限远;对焦超出合焦能力时返回 null */
	public double[] dofRange() {
		if (lens == null) {
			return null;
		}
		double f = focalLength;          // mm
		double n = aperture;
		double s = focusDistance * 1000; // m -> mm
		double hyperfocal = f * f / (n * CIRCLE_OF_CONFUSION) + f;
		if (hyperfocal <= s - f) {
			return null; // 对焦距离超过超焦距:全部清晰
		}
		double near = hyperfocal * s / (hyperfocal + s - f) / 1000;
		double far = hyperfocal * s / (hyperfocal - s - f) / 1000;
		if (far <= 0) {
			return new double[]{near, -1}; // 无限远
		}
		return new double[]{near, far};
	}

	/** 手持稳定:快门慢于 1/焦距 法则 → 抖动警告 */
	public boolean shakeWarning() {
		return lens != null && SHUTTERS[shutterIndex] > 1.0 / focalLength;
	}

	public boolean isActive() {
		return active;
	}

	public boolean isInFocus() {
		return lastTargetDistance >= 0 && Math.abs(lastTargetDistance - focusDistance) < 1.0;
	}

	public String[] hudLines() {
		String focusLine = String.format("[5] 对焦 %.1fm%s", focusDistance,
			lastTargetDistance >= 0 ? " (目标 " + String.format("%.1f", lastTargetDistance) + "m)" : "");
		return new String[]{
			"[1] 焦距 " + (lens != null && lens.type() == LensDefinition.LensType.ZOOM ? focalLength + "mm" : "定焦 " + focalLength + "mm"),
			"[2] 光圈 F" + aperture,
			"[3] 快门 1/" + shutterString(SHUTTERS[shutterIndex]),
			"[4] ISO " + ISOS[isoIndex],
			focusLine
		};
	}

	public int getSelectedParam() {
		return selectedParam;
	}

	public int getCaptureFlashTicks() {
		return captureFlashTicks;
	}

	public PhotoData snapshot() {
		// M2a:拍照时对画面内每只鸟六维评分(纯客户端判定)
		java.util.List<com.birdwatch.client.photo.ScoredBird> birds =
			com.birdwatch.client.photo.PhotoScorer.scoreScene(minecraft, this);
		return new PhotoData(
			lens != null ? lens.id() : "",
			focalLength,
			aperture,
			SHUTTERS[shutterIndex],
			ISOS[isoIndex],
			focusDistance,
			lastTargetDistance,
			(float) computeFov(),
			birds
		);
	}

	private float clampToStops(float value, LensDefinition def) {
		float[] stops = LensRegistry.apertureStops(def);
		return stops[nearestStopIndex(value, stops)];
	}

	private static int nearestStopIndex(float value, float[] stops) {
		int best = 0;
		for (int i = 0; i < stops.length; i++) {
			if (Math.abs(stops[i] - value) < Math.abs(stops[best] - value)) {
				best = i;
			}
		}
		return best;
	}

	private static String shutterString(double shutter) {
		return String.valueOf((int) Math.round(1.0 / shutter));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	/** 拍照瞬间的参数快照(评分/M2 元数据的基础) */
	public record PhotoData(
		String lensId,
		int focalLength,
		float aperture,
		double shutter,
		int iso,
		double focusDistance,
		double targetDistance,
		float fov,
		java.util.List<com.birdwatch.client.photo.ScoredBird> birds
	) {
	}
}
