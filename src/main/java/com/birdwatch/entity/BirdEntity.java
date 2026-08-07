package com.birdwatch.entity;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.bird.BirdSpecies;
import com.birdwatch.bird.BirdSpecies.Habitat;
import com.birdwatch.bird.SpeciesRegistry;
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
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Optional;

/**
 * 鸟实体基类(M4a 数据驱动重构:原 LittleEgretEntity 逻辑整体上移)。
 *
 * 行为状态机(Goal 组合,优先级从高到低):
 * - 惊扰:玩家距离 < 惊扰距离×状态系数 且可见 → ALERTING 警戒冻结(面向威胁、头部追踪)
 *   → 停顿后 TAKEOFFING(原地快速扇翅)→ FLYING(巡航飞离)
 * - 飞行:无重力直线飞离 35 格,前方受阻自动爬升越障,超时强制降落兜底
 * - 昼夜节律:按物种 diurnal 参数决定睡眠窗口(夜晚栖息 / 猫头鹰反相)
 * - 觅食:走到随机点后低头啄食 3~9 秒(动画窗口);湿地物种要求水边
 * - 闲逛:湿地物种倾向水边活动(无水探索飞行);其余物种随机短距蹦跳/短途飞行
 * - 鸣叫:随机间隔(听声辨位音源)
 *
 * 物种差异全部由 {@link BirdSpecies} 参数驱动(惊扰距离 / 栖息行为集 / 音效 / 美术资源前缀);
 * 新增物种 = SpeciesRegistry 加一条记录 + 一个薄实体类。
 *
 * 动画由 GeckoLib 驱动:状态经 SynchedEntityData 同步到客户端,
 * 动画 JSON 键 = species().animationKey(clip),与 RawAnimation 精确匹配。
 */
public class BirdEntity extends PathfinderMob implements GeoEntity {
	/** 行为状态(内存态,不持久化;经 SynchedEntityData 同步,客户端据此播放动画) */
	public enum State { IDLE, FORAGING, TAKEOFFING, FLYING, SLEEPING, ALERTING }

	/** 状态同步字段(客户端动画驱动) */
	private static final EntityDataAccessor<Byte> DATA_STATE =
		SynchedEntityData.defineId(BirdEntity.class, EntityDataSerializers.BYTE);

	/** 玩家可见的水平朝向锥角(±90°,超出视为在背后) */
	private static final double SIGHT_CONE_DEGREES = 90.0;
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
	/** 受惊飞行落地判定:玩家距鸟超过该距离才算安全 */
	private static final double SAFE_LANDING_DISTANCE = 24.0;
	/** 受惊巡航逃离偏置距离:玩家在此范围内,巡航目标偏向远离玩家(受惊飞走倾向) */
	private static final double ESCAPE_BIAS_DISTANCE = 48.0;
	/** 玩家仍在附近时最多续飞次数(防无限飞行) */
	private static final int MAX_FLIGHT_EXTENSIONS = 4;
	/** 水搜索的竖直扫描范围(格,上下对称):覆盖高地/峡谷大高差 */
	private static final int WATER_SCAN_VERTICAL = 32;
	/** 寻路节点预算倍数(长距离飞行寻路用,默认 1.0 可能找不到 80 格外的路径) */
	private static final float PATHFINDER_NODE_MULTIPLIER = 4.0F;
	/** GOTO 到达判定(水平距离,格) */
	private static final double GOTO_ARRIVE_DISTANCE = 3.0;
	/**
	 * 飞行导航速度倍率(全速 = 1.0)。
	 * 注意:MoveControl 实际速度 = moveTo 参数 × 对应属性(FLYING_SPEED),
	 * 物种飞行差异已由 ModEntities 注册的 FLYING_SPEED 属性承担,
	 * 此处必须保持 1.0 —— 若再把物种 flyingSpeed 传进来会双重相乘(实测白鹭飞不动)。
	 */
	private static final double FLIGHT_NAV_SPEED = 1.0;
	/** 地面行走导航速度倍率(移动属性值 × 此倍率 = moveTo 速度参数,1.0=全速) */
	private static final double GROUND_NAV_SPEED_MULTIPLIER = 2.0;
	/** 非湿地觅食/闲逛的随机点采样半径(格) */
	private static final int STROLL_SAMPLE_RADIUS = 12;

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
	/** GOTO 寻路失败后是否已抬升目标重试(防森林/峡谷地形 A* 失败原地落水) */
	private boolean gotoElevated;
	/** 起飞点高度(兜底基准) */
	private double flightBaseY;

	public BirdEntity(EntityType<? extends BirdEntity> type, Level level) {
		super(type, level);
	}

	/**
	 * 物种参数:经实体类型查 SpeciesRegistry。
	 *
	 * 注意:registerGoals() 在 Mob 的 super 构造期间被调用,此时子类实例字段
	 * 尚未赋值 —— 物种不能存实例字段,统一走类型查表(零成本 IdentityHashMap get,
	 * 注册在 ModEntities.registerAll 完成,任何实体构造前已就绪)。
	 */
	public BirdSpecies species() {
		BirdSpecies found = SpeciesRegistry.speciesOf(this).orElse(null);
		if (found == null) {
			throw new IllegalStateException("物种未注册:" + getType());
		}
		return found;
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
	 * 26.2 waypoint 机制防御:旧存档中的鸟(NBT 属性快照不含 waypoint 属性)
	 * 加载时 getAttributeValue(WAYPOINT_TRANSMIT_RANGE) 会抛异常导致玩家进世界卡死。
	 * 鸟不参与 waypoint 传输,直接返回 false 最安全;新实体已补全属性,此覆写为双保险。
	 */
	@Override
	public boolean isTransmittingWaypoint() {
		return false;
	}

	/**
	 * 非湿地鸟自然生成在水域 → 转移到最近干燥可站立点。
	 * 背景:26.2 的 AMBIENT 类别生成位置判定宽松,鸟会直接生成在水面/水中
	 * (用户实测麻雀生成在森林旁水中后淹死);生成时规避比事后自救更干净。
	 * 26.2 生成原因枚举 EntitySpawnReason.NATURAL 对应自然刷新。
	 */
	@Override
	public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor level,
		net.minecraft.world.DifficultyInstance difficulty, net.minecraft.world.entity.EntitySpawnReason reason,
		net.minecraft.world.entity.SpawnGroupData groupData) {
		net.minecraft.world.entity.SpawnGroupData result = super.finalizeSpawn(level, difficulty, reason, groupData);
		if (species().habitat() != Habitat.WETLAND && reason == net.minecraft.world.entity.EntitySpawnReason.NATURAL
			&& isInWater()) {
			BlockPos pos = blockPosition();
			for (int i = 0; i < 96; i++) {
				BlockPos candidate = pos.offset(
					getRandom().nextInt(33) - 16, 0, getRandom().nextInt(33) - 16);
				if (standableAt(candidate)) {
					setPos(candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
					BirdWatchMod.LOGGER.debug("[Bird:{}] 生成于水域,已转移到干燥点 ({}, {}, {})",
						species().id(), candidate.getX(), candidate.getY(), candidate.getZ());
					break;
				}
			}
		}
		return result;
	}

	// ------------------------------------------------------------------
	// GeckoLib 动画(客户端渲染线程驱动)
	// ------------------------------------------------------------------

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
		registrar.add(new AnimationController<BirdEntity>(species().id(), 6, this::handleAnimation));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return animatableCache;
	}

	/** 按行为状态选择循环动画(动画 JSON 键 = species().animationKey(clip),精确匹配) */
	private PlayState handleAnimation(AnimationTest<BirdEntity> test) {
		BirdEntity bird = test.animatable();
		RawAnimation anim;
		switch (bird.getSyncedState()) {
			case TAKEOFFING -> anim = RawAnimation.begin().thenLoop(species().animationKey("fly_takeoff"));
			case FLYING -> anim = RawAnimation.begin().thenLoop(species().animationKey("fly"));
			case SLEEPING -> anim = RawAnimation.begin().thenLoop(species().animationKey("sleep"));
			case FORAGING -> anim = RawAnimation.begin().thenLoop(species().animationKey("forage"));
			case ALERTING -> anim = RawAnimation.begin().thenLoop(species().animationKey("alert"));
			case IDLE -> anim = RawAnimation.begin().thenLoop(
				test.isMoving() ? species().animationKey("walk") : species().animationKey("idle"));
			default -> anim = RawAnimation.begin().thenLoop(species().animationKey("idle"));
		}
		test.setAndContinue(anim);
		return PlayState.CONTINUE;
	}

	// ------------------------------------------------------------------
	// 惊扰 / 起飞 / 飞行
	// ------------------------------------------------------------------

	/** 惊扰距离:玩家速度越快越敏感(数值按物种参数,如静立 4 / 潜行 8 / 行走 12 / 奔跑 20) */
	public double currentScareDistance(Player player) {
		if (player.isShiftKeyDown()) {
			return species().scareSneak();
		}
		double speed = player.getDeltaMovement().horizontalDistanceSqr();
		if (speed > 0.35) { // 约 0.6 格/秒以上
			return species().scareRun();
		}
		if (speed > 0.01) {
			return species().scareWalk();
		}
		return species().scareStill();
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

	/** 目标点可站立:自身不是水,脚下是实心方块(非湿地觅食/闲逛的目标过滤) */
	private boolean standableAt(BlockPos pos) {
		return !isWater(pos)
			&& !level().getBlockState(pos).isSolid()
			&& level().getBlockState(pos.below()).isSolid();
	}

	/** 落点是否远离水域:3 格内无任何水方块(自救落点过滤,防落到水边小块地再落水) */
	private boolean isDryLand(BlockPos pos) {
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				if (isWater(pos.offset(dx, 0, dz))) {
					return false;
				}
			}
		}
		return true;
	}

	/** 头部(眼睛高度)是否没入水中 —— 会窒息的水位判定(水鸟自救触发条件) */
	private boolean isHeadUnderwater() {
		return level().getFluidState(BlockPos.containing(
			position().x, position().y + getEyeHeight(), position().z)).is(net.minecraft.tags.FluidTags.WATER);
	}

	/**
	 * 碰撞箱是否与水源方块碰撞(即时判定,用户定稿):
	 * 遍历碰撞箱覆盖的全部方块,任一为静止/流动水即判定沾水。
	 * 不用 isInWater()(wasTouchingWater 为上一 tick 缓存,时序不可靠,实测偶发漏判);
	 * 不用 isAdjacentToWater(会漏掉「站在浅水中」——中心格被跳过,且干燥平台误判)。
	 */
	private boolean isTouchingWaterSource() {
		net.minecraft.world.phys.AABB box = getBoundingBox();
		int minX = net.minecraft.util.Mth.floor(box.minX);
		int maxX = net.minecraft.util.Mth.floor(box.maxX);
		int minY = net.minecraft.util.Mth.floor(box.minY);
		int maxY = net.minecraft.util.Mth.floor(box.maxY);
		int minZ = net.minecraft.util.Mth.floor(box.minZ);
		int maxZ = net.minecraft.util.Mth.floor(box.maxZ);
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					if (isWater(new BlockPos(x, y, z))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** 鸟能否看到玩家:距离 + 宽朝向锥角(±90°)+ 无遮挡;站在鸟正下方视为被看见 */
	public boolean canSeePlayer(Player player) {
		Vec3 toPlayer = player.position().subtract(position());
		double horizontalDist = toPlayer.horizontalDistance();
		// 正下方特例(水平 3 格内且在鸟下方)
		if (horizontalDist < 3.0 && toPlayer.y < 0) {
			return true;
		}
		// 水平朝向锥角:玩家相对方向与鸟朝向前方夹角
		// 注意:directionFromRotation(xRot=俯仰, yRot=偏航),水平方向必须 xRot=0
		Vec3 forward = Vec3.directionFromRotation(0.0F, getYRot()).multiply(1, 0, 1).normalize();
		Vec3 toPlayerH = toPlayer.multiply(1, 0, 1).normalize();
		if (forward.dot(toPlayerH) < Math.cos(Math.toRadians(SIGHT_CONE_DEGREES))) {
			return false;
		}
		return hasLineOfSight(player);
	}

	/** 警戒结束 → 起飞(快速扇翅阶段,方向 = 远离威胁;湿地物种偏向水面) */
	public void startTakeoff(Vec3 awayDirection) {
		if (state == State.TAKEOFFING || state == State.FLYING) {
			return; // 已在起飞/飞行中
		}
		setState(State.TAKEOFFING);
		takeoffTicks = species().takeoffTicks();
		// 立即升空:无重力 + 上升速度,起飞动画期间可见爬升(否则像原地蹦跳)
		setNoGravity(true);
		setDeltaMovement(0, 0.18, 0);
		Vec3 away = awayDirection.multiply(1, 0, 1).normalize();
		if (away.lengthSqr() < 0.001) {
			away = new Vec3(1, 0, 0);
		}
		// 湿地物种倾向:80 格内有水时,起飞方向 70% 逃离 + 30% 朝水
		if (species().habitat() == Habitat.WETLAND) {
			Optional<Vec3> water = findWaterDirection(species().waterRange());
			if (water.isPresent()) {
				Vec3 blended = away.scale(0.7).add(water.get().scale(0.3));
				if (blended.lengthSqr() > 0.01) {
					away = blended.normalize();
				}
			}
		}
		takeoffDirection = away;
		playSound(species().scared(), 1.0F, 1.0F);
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
		BirdWatchMod.LOGGER.debug("[Bird:{}] GOTO 飞行开始 target=({}, {}, {}) dist={}",
			species().id(), target.x, target.y, target.z, position().distanceTo(target));
		setState(State.FLYING);
		setNoGravity(true);
		getNavigation().stop();
		swapMoveControl(true);
		swapNavigation(true);
		flightMode = FlightMode.GOTO;
		gotoElevated = false;
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
		return nearestPlayer(SAFE_LANDING_DISTANCE) != null;
	}

	/** 最近的非旁观/创造玩家;超过 maxDist(格)返回 null */
	private Player nearestPlayer(double maxDist) {
		if (!(level() instanceof ServerLevel serverLevel)) {
			return null;
		}
		Player nearest = null;
		double best = maxDist * maxDist;
		for (Player player : serverLevel.players()) {
			if (player.isSpectator() || player.isCreative()) {
				continue;
			}
			double d = distanceToSqr(player);
			if (d < best) {
				best = d;
				nearest = player;
			}
		}
		return nearest;
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
	 * 实际速度 = FLIGHT_NAV_SPEED × FLYING_SPEED 属性(SpeciesRegistry 调,白鹭 0.35 慢 / 麻雀 0.6 快)。
	 */
	private void tickFlight() {
		if (--flightTicksRemaining <= 0) {
			// 玩家仍在附近 → 续飞:延长时长,朝远离玩家的方向继续
			if (flightMode == FlightMode.CRUISE && flightExtensions < MAX_FLIGHT_EXTENSIONS
				&& playerStillNear()) {
				flightExtensions++;
				flightTicksRemaining = FLIGHT_MIN_TICKS + getRandom().nextInt(FLIGHT_MAX_TICKS - FLIGHT_MIN_TICKS);
				turnCooldown = 10;
				Player nearest = nearestPlayer(Double.MAX_VALUE);
				if (nearest != null) {
					Vec3 away = position().subtract(nearest.position()).multiply(1, 0, 1).normalize();
					if (away.lengthSqr() < 0.001) {
						away = new Vec3(1, 0, 0);
					}
					Vec3 target = position().add(away.scale(30.0));
					getNavigation().moveTo(target.x, groundBelow() + CRUISE_ALTITUDE, target.z, FLIGHT_NAV_SPEED);
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
				// 首次失败:目标抬升 16 格重试(森林/峡谷地形 A* 失败多为目标被树冠/山脊包围,
				// 抬高后越障可达;否则原地落地可能落回水中,与自救形成死循环)
				if (!gotoElevated) {
					gotoElevated = true;
					flyTarget = new Vec3(flyTarget.x,
						Math.max(groundAt(flyTarget.x, flyTarget.z) + 16.0, position().y + 16.0),
						flyTarget.z);
					BirdWatchMod.LOGGER.debug("[Bird:{}] GOTO 寻路失败,抬升目标重试", species().id());
					if (getNavigation().moveTo(flyTarget.x, flyTarget.y, flyTarget.z, FLIGHT_NAV_SPEED)) {
						return;
					}
				}
				BirdWatchMod.LOGGER.debug("[Bird:{}] GOTO 寻路失败,落地", species().id());
				getNavigation().stop();
				landFlight();
				return;
			}
			return;
		}
		// CRUISE:定时换巡航目标(目标处地面 +8 高度,保证可达),导航自动绕障。
		// 受惊飞走倾向:玩家在 48 格内时,目标偏向远离玩家方向(±60° 随机偏置,
		// 避免呆板直线),让鸟持续逃离而非在玩家头顶打转;飞出范围恢复纯随机。
		if (--turnCooldown <= 0) {
			turnCooldown = TURN_MIN_TICKS + getRandom().nextInt(TURN_MAX_TICKS - TURN_MIN_TICKS);
			double dist = 15.0 + getRandom().nextDouble() * 20.0;
			Vec3 dir;
			Player nearest = nearestPlayer(ESCAPE_BIAS_DISTANCE);
			if (nearest != null) {
				Vec3 away = position().subtract(nearest.position()).multiply(1, 0, 1).normalize();
				if (away.lengthSqr() < 0.001) {
					away = new Vec3(1, 0, 0);
				}
				float yaw = (float) Math.toDegrees(Math.atan2(-away.x, away.z))
					+ (getRandom().nextFloat() * 2.0F - 1.0F) * 60.0F;
				dir = Vec3.directionFromRotation(0.0F, yaw);
			} else {
				float yaw = getYRot() + (getRandom().nextFloat() * 2.0F - 1.0F) * 120.0F;
				dir = Vec3.directionFromRotation(0.0F, yaw);
			}
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
			BirdWatchMod.LOGGER.error("[Bird:{}] tick 异常 pos=({}, {}, {})",
				species().id(), (int) position().x, (int) position().y, (int) position().z, e);
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
				playSound(species().flap(), 0.5F, 1.0F);
			}
		}
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return species().hurt();
	}

	@Override
	protected SoundEvent getDeathSound() {
		return species().death();
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
		goalSelector.addGoal(1, new DrowningEscapeGoal(this));
		goalSelector.addGoal(2, new ScareGoal(this));
		goalSelector.addGoal(2, new TakeoffGoal(this));
		goalSelector.addGoal(3, new FlyGoal(this));
		if (species().habitat() == Habitat.WETLAND) {
			// 湿地行为集:回水 / 水边觅食 / 水边闲逛
			goalSelector.addGoal(4, new ReturnToWaterGoal(this));
			goalSelector.addGoal(5, new SleepGoal(this));
			goalSelector.addGoal(6, new ForageGoal(this, true));
			goalSelector.addGoal(7, new StrollGoal(this));
		} else {
			// 通用行为集:地面觅食 / 随机蹦跳短途飞行
			goalSelector.addGoal(4, new SleepGoal(this));
			goalSelector.addGoal(5, new ForageGoal(this, false));
			goalSelector.addGoal(6, new StrollGoal(this));
		}
		goalSelector.addGoal(8, new AmbientCallGoal(this));
	}

	/**
	 * 平静漂浮(替代原版 FloatGoal):在水中提供温和浮力但不跳跃。
	 * 原版 FloatGoal.tick 每 tick 80% 概率 getJumpControl().jump() ——
	 * 水鸟在浅水行走时会不停跳起,表现为"水中跳跃"(踩坑记录)。
	 */
	static class CalmFloatGoal extends Goal {
		private final BirdEntity bird;

		CalmFloatGoal(BirdEntity bird) {
			this.bird = bird;
		}

		@Override
		public boolean canUse() {
			return bird.isInWater() && bird.getFluidHeight(net.minecraft.tags.FluidTags.WATER) > 0.4F;
		}

		@Override
		public void tick() {
			// 深水:停导航(防止原地旋转)+ 温和浮力;浅水正常涉水
			double depth = bird.getFluidHeight(net.minecraft.tags.FluidTags.WATER);
			if (depth > 0.66F) {
				bird.getNavigation().stop();
			}
			if (bird.getRandom().nextFloat() < 0.8F) {
				bird.setDeltaMovement(bird.getDeltaMovement().x, 0.05, bird.getDeltaMovement().z);
			}
		}
	}

	/**
	 * 落水自救:起飞飞向陆地。触发规则(用户定稿):
	 * - 非水鸟:沾水即自救(鸟不待在水里,哪怕浅水也飞走),落点选择「先高处后岸边」:
	 *   ① 优先飞向地势更高的干燥点(脱离低洼水域环境);
	 *   ② 其次水平 64 格内干燥点(3 格内无水,防再次落水死循环);
	 *   ③ 兜底岸边干燥点(本身无水、脚下实心即可,放宽 3 格内无水 —— 水边没水的地方)。
	 * - 水鸟:仅头部没入水中(会窒息)才救,浅水/深水游泳都是主场。
	 * 背景:26.2 的 AMBIENT 类别自然生成判定宽松,鸟会生成在水面上;
	 * 且鸟的浮力不足以长期维持,头在水下会缺氧窒息死亡(用户实测麻雀淹死)。
	 * 用 GOTO 飞行直飞陆地,比浮游自救更符合鸟的设定(拍翅飞离水面)。
	 */
	static class DrowningEscapeGoal extends Goal {
		private final BirdEntity bird;
		private BlockPos landTarget;

		DrowningEscapeGoal(BirdEntity bird) {
			this.bird = bird;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		/** 非水鸟落点三阶段采样:先高处、再水平干燥、最后岸边兜底 */
		private boolean findNonWaterbirdLanding() {
			BlockPos base = bird.blockPosition();
			int baseY = base.getY();
			// ① 高处优先:水平采样后向下扫描地面,选高于当前高度的干燥点(3 格内无水),
			//    脱离低洼水域环境(深坑/峡谷底的鸟借此飞上高处)
			for (int i = 0; i < 96; i++) {
				BlockPos sample = base.offset(
					bird.getRandom().nextInt(129) - 64, 0, bird.getRandom().nextInt(129) - 64);
				int groundY = (int) Math.floor(bird.groundAt(sample.getX() + 0.5, sample.getZ() + 0.5));
				BlockPos candidate = new BlockPos(sample.getX(), groundY, sample.getZ());
				if (groundY > baseY
					&& bird.standableAt(candidate) && bird.isDryLand(candidate)) {
					landTarget = candidate;
					return true;
				}
			}
			// ② 水平干燥:64 格内「干燥且远离水域」的落点(3 格内无水):
			// 避免飞到水边小块干燥地后再次落水,与自救形成死循环
			for (int i = 0; i < 96; i++) {
				BlockPos candidate = base.offset(
					bird.getRandom().nextInt(129) - 64, 0, bird.getRandom().nextInt(129) - 64);
				if (bird.standableAt(candidate) && bird.isDryLand(candidate)) {
					landTarget = candidate;
					return true;
				}
			}
			// ③ 岸边兜底:本身无水、脚下实心即可(水边没水的地方),放宽 3 格内无水限制
			for (int i = 0; i < 96; i++) {
				BlockPos candidate = base.offset(
					bird.getRandom().nextInt(129) - 64, 0, bird.getRandom().nextInt(129) - 64);
				if (bird.standableAt(candidate)) {
					landTarget = candidate;
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean canUse() {
			if (bird.isFlying() || bird.getState() == State.TAKEOFFING
				|| bird.getState() == State.SLEEPING) {
				return false;
			}
			if (bird.species().habitat() == Habitat.WETLAND) {
				// 水鸟:头部没入水中(会窒息)才救
				if (!bird.isHeadUnderwater()) {
					return false;
				}
			} else if (!bird.isTouchingWaterSource()) {
				// 非水鸟:碰撞箱与水源方块碰撞即自救(用户定稿判定方式:
				// 直接检测碰撞箱覆盖的水方块,不依赖 wasTouchingWater 缓存 ——
				// 缓存存在时序,导致「遇水不一定会飞走」)
				return false;
			}
			return findNonWaterbirdLanding();
		}

		@Override
		public void start() {
			BirdWatchMod.LOGGER.debug("[Bird:{}] 落水自救,起飞 → ({}, {}, {})",
				bird.species().id(), landTarget.getX(), landTarget.getY(), landTarget.getZ());
			bird.enterGotoFlight(new Vec3(landTarget.getX() + 0.5, landTarget.getY(), landTarget.getZ() + 0.5));
		}
	}

	/** 惊扰检测(最高优先级):距离 + 可见 → 警戒冻结;受惊冷却内不触发 */
	static class ScareGoal extends Goal {
		private final BirdEntity bird;
		/** 警戒目标(面向的威胁) */
		private Player alertTarget;
		/** 警戒冻结剩余 tick(约 1.5~2 秒,警戒动画窗口) */
		private int alertTicks;

		ScareGoal(BirdEntity bird) {
			this.bird = bird;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (bird.isFlying() || bird.getState() == State.TAKEOFFING) {
				return false;
			}
			if (!(bird.level() instanceof ServerLevel serverLevel)) {
				return false;
			}
			for (Player player : serverLevel.players()) {
				if (player.isSpectator() || player.isCreative()) {
					continue;
				}
				double dist = bird.distanceToSqr(player);
				if (dist < bird.currentScareDistance(player) * bird.currentScareDistance(player)
					&& bird.canSeePlayer(player)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean canContinueToUse() {
			return bird.getState() == State.ALERTING && alertTicks > 0;
		}

		@Override
		public void start() {
			if (!(bird.level() instanceof ServerLevel serverLevel)) {
				return;
			}
			Player nearest = null;
			double best = Double.MAX_VALUE;
			for (Player player : serverLevel.players()) {
				double d = bird.distanceToSqr(player);
				if (d < best) {
					best = d;
					nearest = player;
				}
			}
			// 警戒阶段:立定、面向威胁,停顿后起飞(时长按物种:白鹭数秒 / 麻雀短暂)
			alertTarget = nearest;
			alertTicks = bird.species().alertTicksMin()
				+ bird.getRandom().nextInt(bird.species().alertTicksMax() - bird.species().alertTicksMin() + 1);
			bird.setState(State.ALERTING);
			bird.getNavigation().stop();
		}

		@Override
		public void tick() {
			if (alertTarget != null) {
				bird.lookAt(alertTarget, 30.0F, 30.0F);
			}
			if (--alertTicks <= 0) {
				Vec3 away = alertTarget != null
					? bird.position().subtract(alertTarget.position())
					: Vec3.directionFromRotation(0.0F, bird.getYRot());
				bird.startTakeoff(away);
			}
		}
	}

	/** 起飞阶段:原地快速扇翅(动画窗口),倒计时结束进入巡航飞行 */
	static class TakeoffGoal extends Goal {
		private final BirdEntity bird;

		TakeoffGoal(BirdEntity bird) {
			this.bird = bird;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			return bird.getState() == State.TAKEOFFING;
		}

		@Override
		public boolean canContinueToUse() {
			return bird.getState() == State.TAKEOFFING;
		}

		@Override
		public void start() {
			bird.getNavigation().stop();
		}

		@Override
		public void tick() {
			if (--bird.takeoffTicks <= 0) {
				bird.enterFlight();
			}
		}
	}

	/** 巡航飞行(实际推进在 aiStep.tickFlight;此处保证不落地行走) */
	static class FlyGoal extends Goal {
		private final BirdEntity bird;

		FlyGoal(BirdEntity bird) {
			this.bird = bird;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			return bird.isFlying();
		}

		@Override
		public boolean canContinueToUse() {
			return bird.isFlying();
		}

		@Override
		public void start() {
			bird.getNavigation().stop();
		}

		@Override
		public void stop() {
			bird.setNoGravity(false);
		}
	}

	/** 睡眠:按物种昼夜节律决定窗口(白天活跃鸟夜晚睡;猫头鹰反相) */
	static class SleepGoal extends Goal {
		private final BirdEntity bird;

		SleepGoal(BirdEntity bird) {
			this.bird = bird;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (bird.isFlying() || bird.getState() == State.TAKEOFFING) {
				return false;
			}
			// overworld 时钟:13000(黄昏)~23000(黎明)
			long time = bird.level().getOverworldClockTime() % 24000L;
			boolean night = time >= 13000 && time < 23000;
			return bird.species().diurnal() ? night : !night;
		}

		@Override
		public void start() {
			bird.setState(State.SLEEPING);
			bird.getNavigation().stop();
		}

		@Override
		public void stop() {
			bird.setState(State.IDLE);
		}
	}

	/**
	 * 觅食:先走到目标点(walk 动画),到点站定后才进入 FORAGING(低头啄食动画)。
	 * 湿地物种(water=true)目标必须水边(近水 2 格内);其余物种任意可站立点。
	 * 附近 10 格内找不到合格目标 → 本次不捕食。
	 */
	static class ForageGoal extends Goal {
		private final BirdEntity bird;
		private final boolean water;
		private int restTicks;
		private BlockPos walkTarget;

		ForageGoal(BirdEntity bird, boolean water) {
			this.bird = bird;
			this.water = water;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (bird.isFlying() || bird.getState() == State.TAKEOFFING
				|| bird.getState() == State.SLEEPING || bird.getState() == State.ALERTING) {
				return false;
			}
			if (!bird.getNavigation().isDone()
				|| bird.getRandom().nextInt(bird.species().forageChanceDivider()) != 0) {
				return false;
			}
			// 随机采样目标(最多 8 次尝试);湿地要求水边;
			// 其余要求干燥可站立 + 远离水域(3 格内无水)—— 非水鸟不走进水域环境,
			// 否则会站到深坑/峡谷底的干燥平台上觅食,视觉上像「待在水里」(实测踩坑)
			BlockPos base = bird.blockPosition();
			for (int i = 0; i < 8; i++) {
				BlockPos candidate = base.offset(
					bird.getRandom().nextInt(21) - 10, 0, bird.getRandom().nextInt(21) - 10);
				if (water ? bird.nearWater(candidate)
					: bird.standableAt(candidate) && bird.isDryLand(candidate)) {
					walkTarget = candidate;
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean canContinueToUse() {
			return !bird.isFlying() && (!bird.getNavigation().isDone() || restTicks > 0);
		}

		@Override
		public void start() {
			// 走路阶段保持 IDLE(走 walk 动画);到点后 tick 里才切 FORAGING
			// 啄食时长按物种:白鹭蹲守(10~20 秒)/ 麻雀啄几下就走(2~4 秒)
			restTicks = bird.species().forageRestMin() + bird.getRandom()
				.nextInt(bird.species().forageRestMax() - bird.species().forageRestMin() + 1);
			bird.getNavigation().moveTo(walkTarget.getX() + 0.5, walkTarget.getY(),
				walkTarget.getZ() + 0.5, bird.species().movementSpeed() * GROUND_NAV_SPEED_MULTIPLIER);
		}

		@Override
		public void tick() {
			if (bird.getNavigation().isDone()) {
				if (bird.getState() != State.FORAGING) {
					bird.setState(State.FORAGING); // 站定才开始啄食
				}
				restTicks--;
			}
		}

		@Override
		public void stop() {
			bird.setState(State.IDLE);
		}
	}

	/** 回水(湿地专属):距水超过阈值 → 定向飞向最近的水;范围内没水交给闲逛随机探索 */
	static class ReturnToWaterGoal extends Goal {
		private final BirdEntity bird;
		private BlockPos waterTarget;

		ReturnToWaterGoal(BirdEntity bird) {
			this.bird = bird;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (bird.isFlying() || bird.getState() == State.TAKEOFFING
				|| bird.getState() == State.SLEEPING || bird.getState() == State.ALERTING) {
				return false;
			}
			if (--bird.returnWaterCooldown > 0) {
				return false;
			}
			// 阈值内有水 → 栖息范围正常,无需回水
			if (bird.waterWithin(bird.species().waterFlyThreshold())) {
				return false;
			}
			// 阈值外 → 决定性触发(可打断走路):寻水半径内找水
			Optional<BlockPos> water = bird.findWaterPosition(bird.species().waterRange());
			if (water.isPresent()) {
				waterTarget = water.get();
				return true;
			}
			return false;
		}

		@Override
		public void start() {
			bird.returnWaterCooldown = 100; // 触发后 5 秒冷却防抖
			BirdWatchMod.LOGGER.debug("[Bird:{}] 起飞回水 target=({}, {}, {}) dist={}",
				bird.species().id(), waterTarget.getX(), waterTarget.getY(), waterTarget.getZ(),
				bird.blockPosition().distManhattan(waterTarget));
			bird.enterGotoFlight(new Vec3(waterTarget.getX() + 0.5, waterTarget.getY(), waterTarget.getZ() + 0.5));
		}
	}

	/**
	 * 闲逛:
	 * - 湿地:有水面朝水边活动(近水飞行概率低),80 格内无水 → 长距离探索飞行;
	 * - 其余:随机短距蹦跳(可站立点),距离够远且概率命中 → 短途飞行跳跃。
	 */
	static class StrollGoal extends Goal {
		private final BirdEntity bird;
		private BlockPos target;
		private boolean fly;

		StrollGoal(BirdEntity bird) {
			this.bird = bird;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (bird.isFlying() || bird.getState() == State.TAKEOFFING
				|| bird.getState() == State.SLEEPING || bird.getState() == State.ALERTING) {
				return false;
			}
			if (!bird.getNavigation().isDone() || bird.getRandom().nextInt(120) != 0) {
				return false;
			}
			BlockPos base = bird.blockPosition();
			if (bird.species().habitat() == Habitat.WETLAND) {
				// 80 格内有水 → 明确朝采到的水位置移动(水源倾向可见)
				Optional<BlockPos> water = bird.findWaterPosition(bird.species().waterRange());
				if (water.isPresent()) {
					BlockPos w = water.get();
					target = w.offset(bird.getRandom().nextInt(7) - 3, 0, bird.getRandom().nextInt(7) - 3);
					fly = bird.getRandom().nextFloat() < bird.species().strollFlyChanceNearWater();
					return true;
				}
				// 80 格内无水 → 长距离探索飞行:覆盖式移动,落地后搜水窗口随位置推移
				float yaw = bird.getYRot() + (bird.getRandom().nextFloat() * 2.0F - 1.0F) * 160.0F;
				double dist = bird.species().strollFlyMinDist()
					+ bird.getRandom().nextDouble() * (bird.species().strollFlyMaxDist() - bird.species().strollFlyMinDist());
				Vec3 dir = Vec3.directionFromRotation(0.0F, yaw);
				Vec3 p = bird.position().add(dir.scale(dist));
				target = BlockPos.containing(p.x, bird.position().y, p.z);
				fly = bird.getRandom().nextFloat() < bird.species().strollFlyChanceExplore();
				return true;
			}
			// 非湿地:随机短距蹦跳(要求远离水域,理由同 ForageGoal);距离够远才用短途飞行
			for (int i = 0; i < 8; i++) {
				BlockPos candidate = base.offset(
					bird.getRandom().nextInt(STROLL_SAMPLE_RADIUS * 2 + 1) - STROLL_SAMPLE_RADIUS,
					0, bird.getRandom().nextInt(STROLL_SAMPLE_RADIUS * 2 + 1) - STROLL_SAMPLE_RADIUS);
				if (bird.standableAt(candidate) && bird.isDryLand(candidate)) {
					target = candidate;
					fly = base.distManhattan(candidate) > 6
						&& bird.getRandom().nextFloat() < bird.species().strollFlyChanceNearWater();
					return true;
				}
			}
			// 8 次采样都找不到「远离水域的干燥点」(深坑/峡谷底被水包围的干燥平台):
			// 触发探索飞行离开水域环境 —— 否则鸟被困在原地,视觉上「待在水里不飞走」
			float yaw = bird.getYRot() + (bird.getRandom().nextFloat() * 2.0F - 1.0F) * 160.0F;
			double dist = 15.0 + bird.getRandom().nextDouble() * 15.0;
			Vec3 dir = Vec3.directionFromRotation(0.0F, yaw);
			Vec3 p = bird.position().add(dir.scale(dist));
			target = BlockPos.containing(p.x, bird.position().y, p.z);
			fly = true;
			return true;
		}

		@Override
		public void start() {
			if (fly) {
				bird.enterGotoFlight(new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5));
			} else {
				bird.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
					bird.species().movementSpeed() * GROUND_NAV_SPEED_MULTIPLIER);
			}
		}
	}

	/** 随机鸣叫(听声辨位音源):间隔 2~10 分钟(游戏内) */
	static class AmbientCallGoal extends Goal {
		private final BirdEntity bird;

		AmbientCallGoal(BirdEntity bird) {
			this.bird = bird;
		}

		@Override
		public boolean canUse() {
			if (bird.isFlying() || bird.getState() == State.TAKEOFFING
				|| bird.getState() == State.SLEEPING || bird.getState() == State.ALERTING) {
				return false;
			}
			if (--bird.callCooldown > 0) {
				return false;
			}
			bird.callCooldown = 2400 + bird.getRandom().nextInt(9600);
			return true;
		}

		@Override
		public void start() {
			bird.playSound(bird.species().ambient(), 1.0F, 0.9F + bird.getRandom().nextFloat() * 0.3F);
		}
	}
}
