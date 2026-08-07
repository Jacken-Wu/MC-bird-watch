package com.birdwatch.client.photo;

import com.birdwatch.bird.SpeciesRegistry;
import com.birdwatch.client.CameraSession;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 六维评分(M2a):
 * 对焦质量 / 画面占比 / 运动模糊(含稳定性)/ 噪点 / 遮挡。
 *
 * 拍摄判定纯客户端(设计定稿,休闲定位无防作弊);
 * 只对「画面内」的鸟评分 —— 相机视锥内(纵向 FOV)且距离 ≤ 100m。
 *
 * 权重:对焦 30% / 占比 25% / 运动模糊 20% / 噪点 15% / 遮挡 10%。
 */
public final class PhotoScorer {
	private static final double W_FOCUS = 0.30;
	private static final double W_COMPOSITION = 0.25;
	private static final double W_MOTION = 0.20;
	private static final double W_NOISE = 0.15;
	private static final double W_OCCLUSION = 0.10;

	/** 检测半径(米) */
	private static final double SCAN_RADIUS = 100.0;
	/** 全画幅弥散圆(mm),与 DoF 后处理一致 */
	private static final double COC_THRESHOLD = 0.03;
	/** 参考画面高度(像素,评分用归一化尺寸) */
	private static final double SCREEN_H = 1080.0;
	/** 相机到采样点的最大射线长度 */
	private static final double RAY_LENGTH = 120.0;

	private PhotoScorer() {
	}

	/** 对画面内每只鸟独立评分,按得分降序返回 */
	public static List<ScoredBird> scoreScene(Minecraft mc, CameraSession session) {
		List<ScoredBird> result = new ArrayList<>();
		if (mc.level == null || mc.player == null || session.getLens() == null) {
			return result;
		}
		Camera camera = mc.gameRenderer.mainCamera();
		Vector3f forwardF = camera.rotation().transform(new Vector3f(0, 0, -1));
		Vec3 forward = new Vec3(forwardF.x, forwardF.y, forwardF.z).normalize();
		double fovRad = Math.toRadians(session.computeFov());
		if (fovRad <= 0) {
			return result;
		}
		Vec3 camPos = camera.position();

		for (LivingEntity bird : inView(livingEntitiesInRange(mc, camera), camPos, forward, fovRad)) {
			Optional<String> species = SpeciesRegistry.speciesIdOf(bird);
			if (species.isEmpty()) {
				continue;
			}
			result.add(scoreBird(mc, session, bird, camera, camPos, forward, fovRad));
		}
		result.sort((a, b) -> Integer.compare(b.score(), a.score()));
		return result;
	}

	/**
	 * 画面内的原版生物图鉴实体 id(M4b:拍照解锁,不评分)。
	 * 与 scoreScene 共用视锥扫描;原版生物即使不在鸟物种清单也识别。
	 */
	public static List<String> detectBestiary(Minecraft mc, CameraSession session) {
		List<String> result = new ArrayList<>();
		if (mc.level == null || mc.player == null || session.getLens() == null) {
			return result;
		}
		Camera camera = mc.gameRenderer.mainCamera();
		Vector3f forwardF = camera.rotation().transform(new Vector3f(0, 0, -1));
		Vec3 forward = new Vec3(forwardF.x, forwardF.y, forwardF.z).normalize();
		double fovRad = Math.toRadians(session.computeFov());
		if (fovRad <= 0) {
			return result;
		}
		Vec3 camPos = camera.position();
		for (LivingEntity entity : inView(livingEntitiesInRange(mc, camera), camPos, forward, fovRad)) {
			com.birdwatch.bird.BestiaryRegistry.idOf(entity.getType())
				.ifPresent(result::add);
		}
		return result;
	}

	/** 相机 100 米内全部活体实体 */
	private static List<LivingEntity> livingEntitiesInRange(Minecraft mc, Camera camera) {
		AABB box = new AABB(camera.position().x - SCAN_RADIUS, camera.position().y - SCAN_RADIUS,
			camera.position().z - SCAN_RADIUS, camera.position().x + SCAN_RADIUS,
			camera.position().y + SCAN_RADIUS, camera.position().z + SCAN_RADIUS);
		List<LivingEntity> result = new ArrayList<>();
		for (Entity entity : mc.level.getEntities(camera.entity(), box, e -> true)) {
			if (entity instanceof LivingEntity living) {
				result.add(living);
			}
		}
		return result;
	}

	/** 视锥筛选:实体中心与视线夹角 < 纵向 FOV/2(加 0.05rad 边缘容差) */
	private static List<LivingEntity> inView(List<LivingEntity> entities, Vec3 camPos, Vec3 forward, double fovRad) {
		List<LivingEntity> result = new ArrayList<>();
		for (LivingEntity e : entities) {
			Vec3 to = e.getBoundingBox().getCenter().subtract(camPos);
			double dist = to.length();
			if (dist > SCAN_RADIUS || dist < 0.5) {
				continue;
			}
			double angle = Math.acos(Mth.clamp(forward.dot(to.normalize()), -1, 1));
			if (angle <= fovRad / 2 + 0.05) {
				result.add(e);
			}
		}
		return result;
	}

	private static ScoredBird scoreBird(Minecraft mc, CameraSession session, LivingEntity bird,
		Camera camera, Vec3 camPos, Vec3 forward, double fovRad) {
		// 身体采样点:中心、头部、胸、左、右(包围盒几何)
		AABB box = bird.getBoundingBox();
		Vec3 center = box.getCenter();
		List<Vec3> samples = List.of(
			center,
			new Vec3(center.x, box.maxY, center.z),
			new Vec3(center.x, box.minY + box.getYsize() * 0.6, center.z),
			new Vec3(box.minX + box.getXsize() * 0.15, box.minY + box.getYsize() * 0.5, center.z),
			new Vec3(box.maxX - box.getXsize() * 0.15, box.minY + box.getYsize() * 0.5, center.z)
		);

		double focus = scoreFocus(session, camPos, samples);
		double composition = scoreComposition(camPos, box, fovRad);
		double stability = scoreStability(mc);
		double motion = scoreMotion(session, bird, camPos, stability, fovRad, mc.player);
		double noise = scoreNoise(mc, bird);
		double occlusion = scoreOcclusion(mc, camPos, camera.entity(), samples);

		int total = (int) Math.round(W_FOCUS * focus + W_COMPOSITION * composition + W_MOTION * motion
			+ W_NOISE * noise + W_OCCLUSION * occlusion);
		String species = SpeciesRegistry.speciesIdOf(bird).orElse("unknown");
		return new ScoredBird(species, total, ScoredBird.tierOf(total),
			Math.round(focus), Math.round(composition), Math.round(motion),
			Math.round(noise), Math.round(occlusion), Math.round(stability));
	}

	/** 对焦质量:全部采样点弥散圆 ≤ 阈值 → 满分;否则按最大 CoC 线性衰减 */
	private static double scoreFocus(CameraSession session, Vec3 camPos, List<Vec3> samples) {
		double f = session.getFocalLength();
		double n = session.getAperture();
		double sf = session.getFocusDistance() * 1000; // m -> mm
		double maxCoc = 0;
		for (Vec3 sample : samples) {
			double s = sample.distanceTo(camPos) * 1000; // m -> mm
			if (s <= 0) {
				continue;
			}
			double coc = f * f / n * Math.abs(1.0 / s - 1.0 / sf); // mm
			maxCoc = Math.max(maxCoc, coc);
		}
		if (maxCoc <= COC_THRESHOLD) {
			return 100;
		}
		return Mth.clamp(100 - (maxCoc - COC_THRESHOLD) / 0.10 * 100, 0, 100);
	}

	/** 画面占比:鸟的纵向角占比,15%~60% 为甜蜜区 */
	private static double scoreComposition(Vec3 camPos, AABB box, double fovRad) {
		double dist = camPos.distanceTo(box.getCenter());
		if (dist <= 0.5) {
			return 0;
		}
		double height = box.getYsize();
		// 鸟在画面中的纵向角占比
		double ratio = Math.atan((height / 2) / dist) / (fovRad / 2);
		if (ratio >= 0.15 && ratio <= 0.60) {
			return 100;
		}
		if (ratio < 0.15) {
			return Mth.clamp(ratio / 0.15 * 100, 0, 100);
		}
		// 太近:1.2 倍画面高 → 0 分
		return Mth.clamp(100 - (ratio - 0.60) / 0.60 * 100, 0, 100);
	}

	/**
	 * 运动模糊:鸟速度 × 快门 = 画面位移(px)。
	 * 稳定性并入:玩家移动等效附加位移(静止 ×0.3 / 潜行 ×0.5 / 走 ×1.0 / 跑 ×1.5)。
	 */
	private static double scoreMotion(CameraSession session, LivingEntity bird, Vec3 camPos,
		double stability, double fovRad, Player player) {
		double dist = Math.max(1.0, camPos.distanceTo(bird.getBoundingBox().getCenter()));
		double pixelsPerMeter = (SCREEN_H / 2) / (dist * Math.tan(fovRad / 2));
		double birdSpeed = bird.getDeltaMovement().length() * 20; // 格/秒
		double playerSpeed = player != null ? player.getDeltaMovement().horizontalDistance() * 20 : 0;
		double stabFactor = stability <= 30 ? 1.5 : stability <= 55 ? 1.0 : stability <= 85 ? 0.5 : 0.3;
		double displacementPx = (birdSpeed + playerSpeed * stabFactor) * session.getShutter() * pixelsPerMeter;
		if (displacementPx <= 1.0) {
			return 100;
		}
		return Mth.clamp(100 - (displacementPx - 1.0) * 25, 0, 100);
	}

	/** 稳定性:玩家移动状态 → 0~100(并入运动模糊的系数依据) */
	private static double scoreStability(Minecraft mc) {
		if (mc.player == null) {
			return 100;
		}
		if (mc.player.isShiftKeyDown()) {
			return 100; // 潜行稳
		}
		double speed = mc.player.getDeltaMovement().horizontalDistance() * 20;
		if (speed < 0.1) {
			return 85; // 静止
		}
		if (speed < 2.0) {
			return 55; // 走路
		}
		return 20; // 奔跑
	}

	/** 噪点:鸟位置光照 × ISO(高感光度 + 暗处 → 噪点多) */
	private static double scoreNoise(Minecraft mc, LivingEntity bird) {
		int light = mc.level.getLightEngine().getRawBrightness(
			BlockPos.containing(bird.getBoundingBox().getCenter()), mc.level.getSkyDarken());
		double isoStops = Math.log(CameraSession.get().getIso() / 100.0) / Math.log(2);
		double penalty = isoStops * 6.0 + (15 - light) * 4.0;
		return Mth.clamp(100 - penalty, 0, 100);
	}

	/** 遮挡:相机→采样点射线命中方块比例的反向 */
	private static double scoreOcclusion(Minecraft mc, Vec3 camPos, Entity viewer, List<Vec3> samples) {
		int visible = 0;
		for (Vec3 sample : samples) {
			Vec3 dir = sample.subtract(camPos);
			if (dir.lengthSqr() > RAY_LENGTH * RAY_LENGTH) {
				dir = dir.normalize().scale(RAY_LENGTH);
			}
			var hit = mc.level.clip(new ClipContext(camPos, camPos.add(dir),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
			if (hit.getType() == HitResult.Type.MISS) {
				visible++;
			}
		}
		return visible * 100.0 / samples.size();
	}
}
