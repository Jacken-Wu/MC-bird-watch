package com.birdwatch.entity;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.registry.ModSounds;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.PlayState;
import com.geckolib.animation.state.AnimationTest;
import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.instance.InstancedAnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Optional;

/**
 * 小白鹭(Egretta garzetta)—— 湿地涉禽(M2b 重写)。
 *
 * 行为状态机(Goal 组合,优先级从高到低):
 * - 惊扰:玩家距离 < 惊扰距离×状态系数 且可见 → ALERTING 警戒冻结(面向威胁、头部追踪)
 *   → 停顿后 TAKEOFFING(原地快速扇翅)→ FLYING(巡航飞离)
 * - 飞行:无重力直线飞离 35 格,前方受阻自动爬升越障,超时强制降落兜底
 * - 昼夜节律:夜晚 SLEEPING 站立缩颈;白天闲逛/觅食
 * - 觅食:走到随机点后低头啄食 3~9 秒(动画窗口)
 * - 鸣叫:随机间隔(听声辨位音源)
 *
 * 动画由 GeckoLib 驱动:状态经 SynchedEntityData 同步到客户端,
 * 动画 JSON 键与 RawAnimation 精确匹配。
 */
public class LittleEgretEntity extends PathfinderMob implements GeoEntity {
	/** 物种惊扰距离:按玩家速度分档(参照原版猫/狐狸对奔跑玩家的反应) */
	public static final double SCARE_DISTANCE = 8.0;
	/** 静立惊扰距离 */
	private static final double SCARE_STILL = 4.0;
	/** 潜行惊扰距离 */
	private static final double SCARE_SNEAK = 8.0;
	/** 行走惊扰距离 */
	private static final double SCARE_WALK = 12.0;
	/** 奔跑惊扰距离 */
	private static final double SCARE_RUN = 20.0;
	/** 受惊飞行落地判定:玩家距鸟超过该距离才算安全 */
	private static final double SAFE_LANDING_DISTANCE = 24.0;
	/** 玩家仍在附近时最多续飞次数(防无限飞行) */
	private static final int MAX_FLIGHT_EXTENSIONS = 4;
	/** 玩家可见的水平朝向锥角(±90°,超出视为在背后) */
	private static final double SIGHT_CONE_DEGREES = 90.0;
	/** 警戒冻结时长(约 1.5~2 秒,警戒动画展示窗口) */
	private static final int ALERT_TICKS = 30;
	/** 起飞快速扇翅时长(0.6 秒) */
	private static final int TAKEOFF_TICKS = 12;
	/** 巡航飞行总时长下限/上限(12~24 秒,随机) */
	private static final int FLIGHT_MIN_TICKS = 240;
	private static final int FLIGHT_MAX_TICKS = 480;
	/** GOTO 飞行兜底时长(8~12 秒) */
	private static final int GOTO_FLIGHT_MIN_TICKS = 160;
	private static final int GOTO_FLIGHT_MAX_TICKS = 240;
	/** 随机转向间隔(30~70 tick) */
	private static final int TURN_MIN_TICKS = 30;
	private static final int TURN_MAX_TICKS = 70;
	/** 巡航高度(相对地面) */
	private static final double CRUISE_ALTITUDE = 8.0;
	/** 水的搜索范围(格):80 格内有水则栖息活动,超出 8 格强制飞回水边(须贴身觅食) */
	private static final double WATER_RANGE = 80.0;
	private static final double WATER_FLY_THRESHOLD = 8.0;
	/** 水搜索的竖直扫描范围(格,上下对称):覆盖高地/峡谷大高差 */
	private static final int WATER_SCAN_VERTICAL = 32;
	/** 寻路节点预算倍数(长距离飞行寻路用,默认 1.0 可能找不到 80 格外的路径) */
	private static final float PATHFINDER_NODE_MULTIPLIER = 4.0F;
	/** GOTO 到达判定(水平距离,格) */
	private static final double GOTO_ARRIVE_DISTANCE = 3.0;
	/** 闲逛时选择飞行的概率:水边 30%,无水域探索 80% */
	private static final float STROLL_FLY_CHANCE_NEAR_WATER = 0.3F;
	private static final float STROLL_FLY_CHANCE_EXPLORE = 0.8F;
	/** 飞行导航速度系数 */
	private static final double FLIGHT_NAV_SPEED = 1.0;

	/** 行为状态(内存态,不持久化;经 SynchedEntityData 同步,客户端据此播放动画) */
	public enum State { IDLE, FORAGING, TAKEOFFING, FLYING, SLEEPING, ALERTING }

	/** 状态同步字段(客户端动画驱动) */
	private static final EntityDataAccessor<Byte> DATA_STATE =
		SynchedEntityData.defineId(LittleEgretEntity.class, EntityDataSerializers.BYTE);

	/** GeckoLib 动画实例缓存(逐实体) */
	private final AnimatableInstanceCache animatableCache = new InstancedAnimatableInstanceCache(this);

	/** 飞行模式:CRUISE 巡航(随机转向、定时落地)/ GOTO 定向(飞向目标点,到达落地) */
	private enum FlightMode { CRUISE, GOTO }

	private State state = State.IDLE;
	/** 起飞方向(远离威胁的水平单位向量) */
	private Vec3 takeoffDirection = new Vec3(1, 0, 0);
	/** 当前飞行模式 */
	private FlightMode flightMode = FlightMode.CRUISE;
	/** GOTO 模式的目标点 */
	private Vec3 flyTarget;
	/** 鸣叫冷却(随机间隔) */
	private int callCooldown;
	/** 扇翅音效冷却(飞行中周期性触发) */
	private int flapSoundCooldown;
	/** 起飞倒计时(TAKEOFFING 状态,TakeoffGoal 驱动) */
	private int takeoffTicks;
	/** 飞行剩余时长(FLYING 状态,归零落地) */
	private int flightTicksRemaining;
	/** 随机转向倒计时(CRUISE 换目标) */
	private int turnCooldown;
	/** 玩家在附近时的续飞次数 */
	private int flightExtensions;
	/** 回水触发冷却(防抖) */
	private int returnWaterCooldown;
	/** GOTO 卡住检测:上次位置与连续无位移 tick 数 */
	private Vec3 lastFlyPos = Vec3.ZERO;
	private int stuckTicks;
	/** 起飞点高度(兜底基准) */
	private double flightBaseY;

	public LittleEgretEntity(EntityType<? extends LittleEgretEntity> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_STATE, (byte) 0);
	}

	/** 状态变更唯一入口:本地字段 + 同步数据一起更新(不持久化到 NBT) */
	private void setState(State s) {
		if (state == s) {
			return;
		}
		state = s;
		entityData.set(DATA_STATE, (byte) s.ordinal());
	}

	/** 客户端安全读取(客户端实例经同步数据拿到状态,供动画/渲染使用) */
	public State getSyncedState() {
		return State.values()[entityData.get(DATA_STATE)];
	}

	public State getState() {
		return state;
	}

	/**
	 * 26.2 waypoint 机制防御:旧存档中的白鹭(NBT 属性快照不含 waypoint 属性)
	 * 加载时 getAttributeValue(WAYPOINT_TRANSMIT_RANGE) 会抛异常导致玩家进世界卡死。
	 * 白鹭不参与 waypoint 传输,直接返回 false 最安全;新实体已补全属性,此覆写为双保险。
	 */
	@Override
	public boolean isTransmittingWaypoint() {
		return false;
	}

	// ------------------------------------------------------------------
	// GeckoLib 动画(客户端渲染线程驱动)
	// ------------------------------------------------------------------

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
		registrar.add(new AnimationController<LittleEgretEntity>("little_egret", 6, this::handleAnimation));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return animatableCache;
	}

	/** 按行为状态选择循环动画(动画 JSON 键与 RawAnimation 精确匹配) */
	private PlayState handleAnimation(AnimationTest<LittleEgretEntity> test) {
		LittleEgretEntity egret = test.animatable();
		RawAnimation anim;
		switch (egret.getSyncedState()) {
			case TAKEOFFING -> anim = RawAnimation.begin().thenLoop("animation.little_egret.fly_takeoff");
			case FLYING -> anim = RawAnimation.begin().thenLoop("animation.little_egret.fly");
			case SLEEPING -> anim = RawAnimation.begin().thenLoop("animation.little_egret.sleep");
			case FORAGING -> anim = RawAnimation.begin().thenLoop("animation.little_egret.forage");
			case ALERTING -> anim = RawAnimation.begin().thenLoop("animation.little_egret.alert");
			case IDLE -> anim = RawAnimation.begin().thenLoop(
				test.isMoving() ? "animation.little_egret.walk" : "animation.little_egret.idle");
			default -> anim = RawAnimation.begin().thenLoop("animation.little_egret.idle");
		}
		test.setAndContinue(anim);
		return PlayState.CONTINUE;
	}

	// ------------------------------------------------------------------
	// 惊扰 / 起飞 / 飞行
	// ------------------------------------------------------------------

	/** 惊扰距离:玩家速度越快越敏感(静立 3 / 潜行 5 / 行走 8 / 奔跑 14 格) */
	public double currentScareDistance(Player player) {
		if (player.isShiftKeyDown()) {
			return SCARE_SNEAK;
		}
		double speed = player.getDeltaMovement().horizontalDistanceSqr();
		if (speed > 0.35) { // 约 0.6 格/秒以上
			return SCARE_RUN;
		}
		if (speed > 0.01) {
			return SCARE_WALK;
		}
		return SCARE_STILL;
	}

	/** 在半径内随机采样找水方块位置;找到返回 BlockPos,否则空(96 次采样,便宜) */
	private Optional<BlockPos> findWaterPosition(double radius) {
		BlockPos pos = blockPosition();
		int r = (int) radius;
		for (int i = 0; i < 96; i++) {
			BlockPos sample = pos.offset(
				getRandom().nextInt(r * 2 + 1) - r, 0, getRandom().nextInt(r * 2 + 1) - r);
			if (isOpenWaterAt(sample)) {
				return Optional.of(sample);
			}
		}
		return Optional.empty();
	}

	/** 在半径内随机采样找水方块;找到返回"朝水"的水平方向,否则空 */
	private Optional<Vec3> findWaterDirection(double radius) {
		return findWaterPosition(radius).map(sample -> {
			BlockPos pos = blockPosition();
			Vec3 dir = new Vec3(sample.getX() - pos.getX(), 0, sample.getZ() - pos.getZ());
			return dir.lengthSqr() > 0.01 ? dir.normalize() : new Vec3(1, 0, 0);
		});
	}

	/** 半径内是否存在水(采样判定) */
	private boolean waterWithin(double radius) {
		return findWaterPosition(radius).isPresent();
	}

	/**
	 * 目标点是否为岸边陆地:目标本身不在水里,且 2 格内是露天水。
	 * (目标本身在水中会触发浮起+行走打架 → 水中跳跃,踩坑记录)
	 */
	private boolean nearWater(BlockPos pos) {
		if (isWater(pos)) {
			return false;
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (isOpenWaterAt(pos.offset(dx, 0, dz))) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 是否为露天水源:水方块 + 上方无遮挡(高度图对比,排除山体/矿洞内的水)。
	 * 注意:① 26.2 FluidState.is(Fluid) 为身份比较,流动水必须显式双流体判定;
	 * ② 不能用 canSeeSky —— 26.2 它是"天空光照≥15"判定,夜晚/黄昏恒为 false,
	 * 会把所有露天水误判成遮挡(踩坑记录)。
	 */
	private boolean isOpenWaterAt(BlockPos pos) {
		// 从上方 +32 向下扫到 -32(每 2 格):覆盖高地/悬崖下的河流等大高差地形
		for (int dy = WATER_SCAN_VERTICAL; dy >= -WATER_SCAN_VERTICAL; dy -= 2) {
			BlockPos p = pos.offset(0, dy, 0);
			if (isWater(p)) {
				return isOpenSkyAt(p);
			}
		}
		return false;
	}

	/**
	 * 露天判定:从目标点向上射线 16 格,无实心阻挡即露天。
	 * 不用高度图:26.2 高度图存"表面+1"(setHeight(x,z,y+1)),比较差一会误判;
	 * 不用 canSeeSky:26.2 它是"天空光照≥15",夜晚恒 false(踩坑记录)。
	 */
	private boolean isOpenSkyAt(BlockPos pos) {
		var hit = level().clip(new ClipContext(
			new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
			new Vec3(pos.getX() + 0.5, pos.getY() + 16.0, pos.getZ() + 0.5),
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		return hit.getType() != HitResult.Type.BLOCK;
	}

	/** 调试:80 格范围扫描统计(命中水样本 / 露天水样本),定位寻水失效环节 */
	private String debugWaterStats() {
		BlockPos pos = blockPosition();
		int water = 0, open = 0;
		for (int i = 0; i < 96; i++) {
			BlockPos sample = pos.offset(
				getRandom().nextInt(161) - 80, 0, getRandom().nextInt(161) - 80);
			if (isWaterAtAnyDepth(sample)) {
				water++;
				if (isOpenWaterAt(sample)) {
					open++;
				}
			}
		}
		return "water=" + water + "/96 open=" + open + "/96";
	}

	/** 采样点 ±32 格深度内是否有水(不要求露天) */
	private boolean isWaterAtAnyDepth(BlockPos pos) {
		for (int dy = WATER_SCAN_VERTICAL; dy >= -WATER_SCAN_VERTICAL; dy -= 2) {
			if (isWater(pos.offset(0, dy, 0))) {
				return true;
			}
		}
		return false;
	}

	/** 静止水或流动水 */
	private boolean isWater(BlockPos pos) {
		FluidState fluid = level().getFluidState(pos);
		if (fluid.isEmpty()) {
			return false;
		}
		Fluid type = fluid.getType();
		return type == Fluids.WATER || type == Fluids.FLOWING_WATER;
	}

	/** 白鹭能否看到玩家:距离 + 宽朝向锥角(±90°)+ 无遮挡;站在鸟正下方视为被看见 */
	public boolean canSeePlayer(Player player) {
		Vec3 toPlayer = player.position().subtract(position());
		double horizontalDist = toPlayer.horizontalDistance();
		// 正下方特例(水平 3 格内且在鸟下方)
		if (horizontalDist < 3.0 && toPlayer.y < 0) {
			return true;
		}
		// 水平朝向锥角:玩家相对方向与鸟朝向前方夹角
		Vec3 forward = Vec3.directionFromRotation(getYRot(), 0.0F).multiply(1, 0, 1).normalize();
		Vec3 toPlayerH = toPlayer.multiply(1, 0, 1).normalize();
		if (forward.dot(toPlayerH) < Math.cos(Math.toRadians(SIGHT_CONE_DEGREES))) {
			return false;
		}
		return hasLineOfSight(player);
	}

	/** 警戒结束 → 起飞(快速扇翅阶段,方向 = 远离威胁,偏向水面) */
	public void startTakeoff(Vec3 awayDirection) {
		if (state == State.TAKEOFFING || state == State.FLYING) {
			return; // 已在起飞/飞行中
		}
		setState(State.TAKEOFFING);
		takeoffTicks = TAKEOFF_TICKS;
		// 立即升空:无重力 + 上升速度,起飞动画期间可见爬升(否则像原地蹦跳)
		setNoGravity(true);
		setDeltaMovement(0, 0.18, 0);
		Vec3 away = awayDirection.multiply(1, 0, 1).normalize();
		if (away.lengthSqr() < 0.001) {
			away = new Vec3(1, 0, 0);
		}
		// 水鸟倾向:48 格内有水时,起飞方向 70% 逃离 + 30% 朝水
		Optional<Vec3> water = findWaterDirection(WATER_RANGE);
		if (water.isPresent()) {
			Vec3 blended = away.scale(0.7).add(water.get().scale(0.3));
			if (blended.lengthSqr() > 0.01) {
				away = blended.normalize();
			}
		}
		takeoffDirection = away;
		playSound(ModSounds.LITTLE_EGRET_SCARED, 1.0F, 1.0F);
		// 面向起飞方向
		setYRot((float) Math.toDegrees(Math.atan2(-takeoffDirection.x, takeoffDirection.z)));
		setYHeadRot(getYRot());
	}

	/** 起飞结束 → 进入巡航飞行(导航随机目标,时长结束自然落地) */
	private void enterFlight() {
		setState(State.FLYING);
		setNoGravity(true);
		getNavigation().stop();
		swapMoveControl(true); // 换飞行移动控制(自动开无重力,3D 移动)
		swapNavigation(true); // 换飞行寻路(3D A* 避障)
		flightMode = FlightMode.CRUISE;
		flightTicksRemaining = FLIGHT_MIN_TICKS + getRandom().nextInt(FLIGHT_MAX_TICKS - FLIGHT_MIN_TICKS);
		turnCooldown = 20;
		flightExtensions = 0;
		lastFlyPos = position();
		stuckTicks = 0;
		flightBaseY = position().y;
		flapSoundCooldown = 6;
	}

	/** 定向飞行:飞向目标点(巡航高度飞行,接近后降落),水平 < 3 格或超时落地 */
	public void enterGotoFlight(Vec3 target) {
		BirdWatchMod.LOGGER.info("[Egret] GOTO 飞行开始 target=({}, {}, {}) dist={}",
			target.x, target.y, target.z, position().distanceTo(target));
		setState(State.FLYING);
		setNoGravity(true);
		getNavigation().stop();
		swapMoveControl(true);
		swapNavigation(true);
		flightMode = FlightMode.GOTO;
		// 目标抬高到巡航高度:长途飞行不穿树林;到达判定只看水平距离,到时自然下降
		flyTarget = new Vec3(target.x, groundAt(target.x, target.z) + CRUISE_ALTITUDE, target.z);
		// 超时按距离缩放(每格 ~4 tick,上限 30 秒)
		double dist = position().distanceTo(flyTarget);
		flightTicksRemaining = (int) Math.min(600.0, GOTO_FLIGHT_MIN_TICKS + dist * 4.0);
		lastFlyPos = position();
		stuckTicks = 0;
		flightBaseY = position().y;
		flapSoundCooldown = 6;
	}

	public boolean isFlying() {
		return state == State.FLYING;
	}

	/** 飞行结束落地(自由落体,鸟类无摔落伤害);换回地面移动控制与地面寻路 */
	private void landFlight() {
		getNavigation().stop();
		setNoGravity(false);
		swapMoveControl(false);
		swapNavigation(false);
		setState(State.IDLE);
		callCooldown = 40;
	}

	/** 玩家是否仍在附近(安全距离内) */
	private boolean playerStillNear() {
		if (!(level() instanceof ServerLevel serverLevel)) {
			return false;
		}
		for (Player player : serverLevel.players()) {
			if (player.isSpectator() || player.isCreative()) {
				continue;
			}
			if (distanceToSqr(player) < SAFE_LANDING_DISTANCE * SAFE_LANDING_DISTANCE) {
				return true;
			}
		}
		return false;
	}

	/** 移动控制切换:飞行用 FlyingMoveControl(3D + 自动无重力),地面用默认 */
	private void swapMoveControl(boolean flying) {
		this.moveControl = flying ? new FlyingMoveControl(this, 20, true) : new MoveControl(this);
	}

	/**
	 * 寻路切换:地面用 GroundPathNavigation(普通路径节点,不抬高);
	 * 飞行用 FlyingPathNavigation(3D A* 避障,节点预算 ×4 支持远距离)。
	 * 地面行走若用飞行寻路会生成抬高节点,默认 MoveControl 朝空中节点转向 → 原地旋转跳跃。
	 */
	private void swapNavigation(boolean flying) {
		if (flying) {
			FlyingPathNavigation nav = new FlyingPathNavigation(this, level());
			nav.setMaxVisitedNodesMultiplier(PATHFINDER_NODE_MULTIPLIER);
			this.navigation = nav;
		} else {
			this.navigation = new GroundPathNavigation(this, level());
		}
	}

	@Override
	protected PathNavigation createNavigation(Level level) {
		return new GroundPathNavigation(this, level);
	}

	/** 正下方地面高度(向下 32 格扫描;扫不到返回当前高度 -8) */
	private double groundBelow() {
		return groundAt(position().x, position().z);
	}

	/** 指定水平位置的地面高度(从 y+32 向下 64 格扫描;扫不到返回调用点高度 -8) */
	private double groundAt(double x, double z) {
		var hit = level().clip(new ClipContext(
			new Vec3(x, position().y + 32, z), new Vec3(x, position().y - 32, z),
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().y : position().y - 8.0;
	}

	/**
	 * 飞行推进(服务端 tick):由 FlyingPathNavigation 3D 寻路驱动
	 * (自动绕障/翻越地形),GOTO 飞向目标、CRUISE 定时换随机目标;时长结束自然落地。
	 */
	private void tickFlight() {
		if (--flightTicksRemaining <= 0) {
			// 玩家仍在附近 → 续飞:延长时长,朝远离玩家的方向继续
			if (flightMode == FlightMode.CRUISE && flightExtensions < MAX_FLIGHT_EXTENSIONS
				&& playerStillNear()) {
				flightExtensions++;
				flightTicksRemaining = FLIGHT_MIN_TICKS + getRandom().nextInt(FLIGHT_MAX_TICKS - FLIGHT_MIN_TICKS);
				turnCooldown = 10;
				if (level() instanceof ServerLevel serverLevel) {
					Player nearest = null;
					double best = Double.MAX_VALUE;
					for (Player p : serverLevel.players()) {
						double d = distanceToSqr(p);
						if (d < best) {
							best = d;
							nearest = p;
						}
					}
					if (nearest != null) {
						Vec3 away = position().subtract(nearest.position()).multiply(1, 0, 1).normalize();
						if (away.lengthSqr() < 0.001) {
							away = new Vec3(1, 0, 0);
						}
						Vec3 target = position().add(away.scale(30.0));
						getNavigation().moveTo(target.x, groundBelow() + CRUISE_ALTITUDE, target.z, FLIGHT_NAV_SPEED);
					}
				}
				return;
			}
			landFlight();
			return;
		}
		if (flightMode == FlightMode.GOTO) {
			// 到达判定
			if (flyTarget.subtract(position()).horizontalDistance() < GOTO_ARRIVE_DISTANCE) {
				landFlight();
				return;
			}
			// 卡住检测:有路径后 3 秒无位移才判定卡住(寻路/启动期间不算)
			if (getNavigation().isInProgress()) {
				Vec3 pos = position();
				if (pos.distanceToSqr(lastFlyPos) < 0.0001) {
					if (++stuckTicks > 60) {
						landFlight();
						return;
					}
				} else {
					stuckTicks = 0;
					lastFlyPos = pos;
				}
			}
			// 先 moveTo 再判结果:换新导航后首 tick 无路径属正常,不能判失败落地
			if (!getNavigation().moveTo(flyTarget.x, flyTarget.y, flyTarget.z, FLIGHT_NAV_SPEED)) {
				BirdWatchMod.LOGGER.info("[Egret] GOTO 寻路失败,落地");
				getNavigation().stop();
				landFlight();
				return;
			}
			return;
		}
		// CRUISE:定时换随机巡航目标(目标处地面 +8 高度,保证可达),导航自动绕障
		if (--turnCooldown <= 0) {
			turnCooldown = TURN_MIN_TICKS + getRandom().nextInt(TURN_MAX_TICKS - TURN_MIN_TICKS);
			float yaw = getYRot() + (getRandom().nextFloat() * 2.0F - 1.0F) * 120.0F;
			double dist = 15.0 + getRandom().nextDouble() * 20.0;
			Vec3 dir = Vec3.directionFromRotation(yaw, 0.0F);
			Vec3 target = position().add(dir.scale(dist));
			getNavigation().moveTo(target.x, groundAt(target.x, target.z) + CRUISE_ALTITUDE, target.z, FLIGHT_NAV_SPEED);
		} else if (!getNavigation().isInProgress()) {
			// 寻路失败时清除移动意图,防止原地旋转/跳跃
			getNavigation().stop();
		}
	}

	@Override
	public void tick() {
		try {
			super.tick();
		} catch (Exception e) {
			// 调试探针:任何 tick 异常直接暴露(否则实体静默冻结)
			BirdWatchMod.LOGGER.error("[Egret] tick 异常 pos=({}, {}, {})",
				(int) position().x, (int) position().y, (int) position().z, e);
		}
	}

	@Override
	public void aiStep() {
		super.aiStep();
		if (!level().isClientSide() && state == State.FLYING) {
			tickFlight();
			// 扇翅音:约 0.75~1.3s 一次
			if (--flapSoundCooldown <= 0) {
				flapSoundCooldown = 15 + getRandom().nextInt(11);
				playSound(ModSounds.LITTLE_EGRET_FLAP, 0.5F, 1.0F);
			}
		}
		// 调试心跳:每 5 秒输出一次完整状态
		if (!level().isClientSide() && tickCount % 100 == 0) {
			BirdWatchMod.LOGGER.info("[Egret] HB state={} pos=({}, {}, {}) nav={} mode={}",
				state, (int) position().x, (int) position().y, (int) position().z,
				getNavigation().isDone() ? "idle" : "busy",
				flightMode);
		}
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return ModSounds.LITTLE_EGRET_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return ModSounds.LITTLE_EGRET_DEATH;
	}

	/** 鸟类不承受摔落伤害(与原版鸡一致;26.2 签名第一参数为 double) */
	@Override
	public boolean causeFallDamage(double fallDistance, float damageMultiplier, DamageSource source) {
		return false;
	}

	/**
	 * 受击即起飞(远离伤害来源)。仅限实体攻击(玩家/生物)——环境伤害
	 * (窒息/火焰等)不触发:卡在树里会反复起飞悬停,表现为行为异常(踩坑记录)。
	 */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		boolean result = super.hurtServer(level, source, amount);
		if (result && state != State.TAKEOFFING && !isFlying()
			&& source.getEntity() != null) {
			startTakeoff(position().subtract(source.getEntity().position()));
		}
		return result;
	}

	// ------------------------------------------------------------------
	// Goals
	// ------------------------------------------------------------------

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new CalmFloatGoal(this));
		goalSelector.addGoal(1, new ScareGoal(this));
		goalSelector.addGoal(2, new TakeoffGoal(this));
		goalSelector.addGoal(3, new FlyGoal(this));
		goalSelector.addGoal(4, new ReturnToWaterGoal(this));
		goalSelector.addGoal(5, new SleepGoal(this));
		goalSelector.addGoal(6, new ForageGoal(this));
		goalSelector.addGoal(7, new WaterSideStrollGoal(this));
		goalSelector.addGoal(8, new AmbientCallGoal(this));
	}

	/**
	 * 平静漂浮(替代原版 FloatGoal):在水中提供温和浮力但不跳跃。
	 * 原版 FloatGoal.tick 每 tick 80% 概率 getJumpControl().jump() ——
	 * 水鸟在浅水行走时会不停跳起,表现为"水中跳跃"(踩坑记录)。
	 */
	static class CalmFloatGoal extends Goal {
		private final LittleEgretEntity egret;

		CalmFloatGoal(LittleEgretEntity egret) {
			this.egret = egret;
		}

		@Override
		public boolean canUse() {
			return egret.isInWater() && egret.getFluidHeight(net.minecraft.tags.FluidTags.WATER) > 0.4F;
		}

		@Override
		public void tick() {
			// 深水:停导航(防止原地旋转)+ 温和浮力;浅水正常涉水
			double depth = egret.getFluidHeight(net.minecraft.tags.FluidTags.WATER);
			if (depth > 0.66F) {
				egret.getNavigation().stop();
			}
			if (egret.getRandom().nextFloat() < 0.8F) {
				egret.setDeltaMovement(egret.getDeltaMovement().x, 0.05, egret.getDeltaMovement().z);
			}
		}
	}

	/** 惊扰检测(最高优先级):距离 + 可见 → 警戒冻结;受惊冷却内不触发 */
	static class ScareGoal extends Goal {
		private final LittleEgretEntity egret;
		/** 警戒目标(面向的威胁) */
		private Player alertTarget;
		/** 警戒冻结剩余 tick(约 1.5~2 秒,警戒动画窗口) */
		private int alertTicks;

		ScareGoal(LittleEgretEntity egret) {
			this.egret = egret;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (egret.isFlying() || egret.getState() == State.TAKEOFFING) {
				return false;
			}
			if (!(egret.level() instanceof ServerLevel serverLevel)) {
				return false;
			}
			for (Player player : serverLevel.players()) {
				if (player.isSpectator() || player.isCreative()) {
					continue;
				}
				double dist = egret.distanceToSqr(player);
				if (dist < egret.currentScareDistance(player) * egret.currentScareDistance(player)
					&& egret.canSeePlayer(player)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean canContinueToUse() {
			return egret.getState() == State.ALERTING && alertTicks > 0;
		}

		@Override
		public void start() {
			if (!(egret.level() instanceof ServerLevel serverLevel)) {
				return;
			}
			Player nearest = null;
			double best = Double.MAX_VALUE;
			for (Player player : serverLevel.players()) {
				double d = egret.distanceToSqr(player);
				if (d < best) {
					best = d;
					nearest = player;
				}
			}
			// 警戒阶段:立定、面向威胁,停顿后起飞
			alertTarget = nearest;
			alertTicks = ALERT_TICKS + egret.getRandom().nextInt(10);
			egret.setState(State.ALERTING);
			egret.getNavigation().stop();
		}

		@Override
		public void tick() {
			if (alertTarget != null) {
				egret.lookAt(alertTarget, 30.0F, 30.0F);
			}
			if (--alertTicks <= 0) {
				Vec3 away = alertTarget != null
					? egret.position().subtract(alertTarget.position())
					: Vec3.directionFromRotation(egret.getYRot(), 0);
				egret.startTakeoff(away);
			}
		}
	}

	/** 起飞阶段:原地快速扇翅(动画窗口),倒计时结束进入巡航飞行 */
	static class TakeoffGoal extends Goal {
		private final LittleEgretEntity egret;

		TakeoffGoal(LittleEgretEntity egret) {
			this.egret = egret;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			return egret.getState() == State.TAKEOFFING;
		}

		@Override
		public boolean canContinueToUse() {
			return egret.getState() == State.TAKEOFFING;
		}

		@Override
		public void start() {
			egret.getNavigation().stop();
		}

		@Override
		public void tick() {
			if (--egret.takeoffTicks <= 0) {
				egret.enterFlight();
			}
		}
	}

	/** 巡航飞行(实际推进在 aiStep.tickFlight;此处保证不落地行走) */
	static class FlyGoal extends Goal {
		private final LittleEgretEntity egret;

		FlyGoal(LittleEgretEntity egret) {
			this.egret = egret;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			return egret.isFlying();
		}

		@Override
		public boolean canContinueToUse() {
			return egret.isFlying();
		}

		@Override
		public void start() {
			egret.getNavigation().stop();
		}

		@Override
		public void stop() {
			egret.setNoGravity(false);
		}
	}

	/** 夜晚栖息:站立缩颈不动 */
	static class SleepGoal extends Goal {
		private final LittleEgretEntity egret;

		SleepGoal(LittleEgretEntity egret) {
			this.egret = egret;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (egret.isFlying() || egret.getState() == State.TAKEOFFING) {
				return false;
			}
			// 夜晚:overworld 时钟 13000~23000(黄昏后到黎明前)
			long time = egret.level().getOverworldClockTime() % 24000L;
			return time >= 13000 && time < 23000;
		}

		@Override
		public void start() {
			egret.setState(State.SLEEPING);
			egret.getNavigation().stop();
		}

		@Override
		public void stop() {
			egret.setState(State.IDLE);
		}
	}

	/**
	 * 觅食(水边):先走到水边目标点(walk 动画),到点站定后才进入
	 * FORAGING(低头啄食动画,不再边走边啄)。
	 * 附近 10 格内找不到水边 → 本次不捕食。
	 */
	static class ForageGoal extends Goal {
		private final LittleEgretEntity egret;
		private int restTicks;
		private BlockPos walkTarget;

		ForageGoal(LittleEgretEntity egret) {
			this.egret = egret;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (egret.isFlying() || egret.getState() == State.TAKEOFFING
				|| egret.getState() == State.SLEEPING || egret.getState() == State.ALERTING) {
				return false;
			}
			if (!egret.getNavigation().isDone() || egret.getRandom().nextInt(40) != 0) {
				return false;
			}
			// 找水边目标(最多 8 次随机尝试);没有水边就不捕食
			BlockPos base = egret.blockPosition();
			for (int i = 0; i < 8; i++) {
				BlockPos candidate = base.offset(
					egret.getRandom().nextInt(21) - 10, 0, egret.getRandom().nextInt(21) - 10);
				if (egret.nearWater(candidate)) {
					walkTarget = candidate;
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean canContinueToUse() {
			return !egret.isFlying() && (!egret.getNavigation().isDone() || restTicks > 0);
		}

		@Override
		public void start() {
			// 走路阶段保持 IDLE(走 walk 动画);到点后 tick 里才切 FORAGING
			restTicks = 80 + egret.getRandom().nextInt(120); // 到点后低头啄食 4~10 秒
			egret.getNavigation().moveTo(walkTarget.getX() + 0.5, walkTarget.getY(),
				walkTarget.getZ() + 0.5, 0.5);
		}

		@Override
		public void tick() {
			if (egret.getNavigation().isDone()) {
				if (egret.getState() != State.FORAGING) {
					egret.setState(State.FORAGING); // 站定才开始啄食
				}
				restTicks--;
			}
		}

		@Override
		public void stop() {
			egret.setState(State.IDLE);
		}
	}

	/** 回水:距水超过 32 格 → 定向飞向最近的水(48 格内);48 格内没水交给闲逛随机探索 */
	static class ReturnToWaterGoal extends Goal {
		private final LittleEgretEntity egret;
		private BlockPos waterTarget;

		ReturnToWaterGoal(LittleEgretEntity egret) {
			this.egret = egret;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (egret.isFlying() || egret.getState() == State.TAKEOFFING
				|| egret.getState() == State.SLEEPING || egret.getState() == State.ALERTING) {
				return false;
			}
			if (--egret.returnWaterCooldown > 0) {
				return false;
			}
			// 调试:每 200 tick 记录一次判定状态
			if (egret.tickCount % 200 == 0) {
				BirdWatchMod.LOGGER.info("[Egret] 回水判定: cooldown={} navDone={} waterNear8={}",
					egret.returnWaterCooldown, egret.getNavigation().isDone(), egret.waterWithin(WATER_FLY_THRESHOLD));
			}
			// 8 格内有水 → 栖息范围正常,无需回水
			if (egret.waterWithin(WATER_FLY_THRESHOLD)) {
				return false;
			}
			// 8 格外 → 决定性触发(可打断走路):80 格范围内找水
			Optional<BlockPos> water = egret.findWaterPosition(WATER_RANGE);
			if (water.isPresent()) {
				waterTarget = water.get();
				if (egret.tickCount % 200 == 0) {
					BirdWatchMod.LOGGER.info("[Egret] 回水判定: 找到水源 dist={}",
						egret.blockPosition().distManhattan(waterTarget));
				}
				return true;
			}
			if (egret.tickCount % 200 == 0) {
				BirdWatchMod.LOGGER.info("[Egret] 回水判定: 未找到水源 {}", egret.debugWaterStats());
			}
			return false;
		}

		@Override
		public void start() {
			egret.returnWaterCooldown = 100; // 触发后 5 秒冷却防抖
			BirdWatchMod.LOGGER.info("[Egret] 起飞回水 target=({}, {}, {}) dist={}",
				waterTarget.getX(), waterTarget.getY(), waterTarget.getZ(),
				egret.blockPosition().distManhattan(waterTarget));
			egret.enterGotoFlight(new Vec3(waterTarget.getX() + 0.5, waterTarget.getY(), waterTarget.getZ() + 0.5));
		}
	}

	/** 水边闲逛:有水时目标优先水边(30% 概率用飞);48 格内无水时随机探索(60% 概率用飞) */
	static class WaterSideStrollGoal extends Goal {
		private final LittleEgretEntity egret;
		private BlockPos target;
		private boolean fly;

		WaterSideStrollGoal(LittleEgretEntity egret) {
			this.egret = egret;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (egret.isFlying() || egret.getState() == State.TAKEOFFING
				|| egret.getState() == State.SLEEPING || egret.getState() == State.ALERTING) {
				return false;
			}
			if (!egret.getNavigation().isDone() || egret.getRandom().nextInt(120) != 0) {
				return false;
			}
			BlockPos base = egret.blockPosition();
			// 80 格内有水 → 明确朝采到的水位置移动(水源倾向可见)
			Optional<BlockPos> water = egret.findWaterPosition(WATER_RANGE);
			if (water.isPresent()) {
				BlockPos w = water.get();
				target = w.offset(egret.getRandom().nextInt(7) - 3, 0, egret.getRandom().nextInt(7) - 3);
				fly = egret.getRandom().nextFloat() < STROLL_FLY_CHANCE_NEAR_WATER;
				return true;
			}
			// 80 格内无水 → 长距离探索飞行(40~80 格):覆盖式移动,落地后搜水窗口随位置推移
			float yaw = egret.getYRot() + (egret.getRandom().nextFloat() * 2.0F - 1.0F) * 160.0F;
			double dist = 40.0 + egret.getRandom().nextDouble() * 40.0;
			Vec3 dir = Vec3.directionFromRotation(yaw, 0.0F);
			Vec3 p = egret.position().add(dir.scale(dist));
			target = BlockPos.containing(p.x, egret.position().y, p.z);
			fly = true;
			return true;
		}

		@Override
		public void start() {
			if (fly) {
				egret.enterGotoFlight(new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5));
			} else {
				egret.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
			}
		}
	}

	/** 随机鸣叫(听声辨位音源):间隔 2~10 分钟(游戏内) */
	static class AmbientCallGoal extends Goal {
		private final LittleEgretEntity egret;

		AmbientCallGoal(LittleEgretEntity egret) {
			this.egret = egret;
		}

		@Override
		public boolean canUse() {
			if (egret.isFlying() || egret.getState() == State.TAKEOFFING
				|| egret.getState() == State.SLEEPING || egret.getState() == State.ALERTING) {
				return false;
			}
			if (--egret.callCooldown > 0) {
				return false;
			}
			egret.callCooldown = 2400 + egret.getRandom().nextInt(9600);
			return true;
		}

		@Override
		public void start() {
			egret.playSound(ModSounds.LITTLE_EGRET_AMBIENT, 1.0F, 0.9F + egret.getRandom().nextFloat() * 0.3F);
		}
	}
}
