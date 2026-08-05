package com.birdwatch.entity;

import com.birdwatch.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * 白鹭 —— 湿地涉禽(M2a)。
 *
 * 行为状态机(Goal 组合):
 * - 惊扰(最高优先级):玩家距离 < 惊扰距离×状态系数 且可见(朝向+LOS)→ 受惊起飞
 * - 飞行:无重力爬升后朝远离玩家方向直线飞离 30+ 格,落地后受惊冷却半小时(游戏内)
 * - 昼夜节律:夜晚栖息不动,白天觅食/闲逛
 * - 鸣叫:随机鸣叫(听声辨位音源)
 *
 * M2a 为静态模型:飞行表现为悬空直线移动,扇翅动画 M2b 接入。
 * 迷彩系数暂为 1.0(M5 接入四件套)。
 */
public class HeronEntity extends PathfinderMob {
	/** 物种惊扰距离:距离 < 惊扰距离 × 状态系数 且可见即受惊 */
	public static final double SCARE_DISTANCE = 8.0;
	/** 受惊后飞离距离 */
	private static final double FLY_AWAY_DISTANCE = 35.0;
	/** 飞行爬升高度(相对起飞点) */
	private static final double FLY_ALTITUDE = 6.0;
	/** 飞行速度(格/秒) */
	private static final double FLY_SPEED = 1.6;
	/** 受惊冷却:约半小时游戏内(36000 tick) */
	private static final long SCARE_COOLDOWN_TICKS = 36000L;
	/** 玩家可见的水平朝向锥角(±60°,超出视为在背后) */
	private static final double SIGHT_CONE_DEGREES = 60.0;

	/** 行为状态(内存态,不持久化) */
	public enum State { IDLE, FORAGING, FLYING, SLEEPING }

	private State state = State.IDLE;
	/** 受惊冷却到期时刻(gameTime) */
	private long scaredCooldownUntil;
	/** 飞行目标点 */
	private Vec3 flyTarget;
	/** 鸣叫冷却(随机间隔) */
	private int callCooldown;

	public HeronEntity(EntityType<? extends HeronEntity> type, Level level) {
		super(type, level);
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

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(1, new HeronScareGoal(this));
		goalSelector.addGoal(2, new HeronFlyGoal(this));
		goalSelector.addGoal(3, new HeronSleepGoal(this));
		goalSelector.addGoal(4, new HeronForageGoal(this));
		goalSelector.addGoal(5, new HeronAmbientCallGoal(this));
		goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6));
	}

	/** 白鹭(常见)惊扰距离 8 格;迷彩系数占位 1.0(M5 接入) */
	public double currentScareDistance(Player player) {
		double coefficient = switch (playerMovementState(player)) {
			case RUNNING -> 1.5;
			case WALKING -> 1.0;
			case SNEAKING -> 0.5;
			case STILL -> 0.3;
		};
		return SCARE_DISTANCE * coefficient;
	}

	private MovementState playerMovementState(Player player) {
		if (player.isShiftKeyDown()) {
			return MovementState.SNEAKING;
		}
		double speed = player.getDeltaMovement().horizontalDistanceSqr();
		if (speed > 0.35) { // 约 0.6 格/秒以上
			return MovementState.RUNNING;
		}
		if (speed > 0.01) {
			return MovementState.WALKING;
		}
		return MovementState.STILL;
	}

	private enum MovementState { RUNNING, WALKING, SNEAKING, STILL }

	/** 白鹭能否看到玩家:距离 + 朝向锥角 + 无遮挡;站在鸟正下方视为被看见 */
	public boolean canSeePlayer(Player player) {
		Vec3 toPlayer = player.position().subtract(position());
		double horizontalDist = toPlayer.horizontalDistance();
		// 正下方特例(水平 2 格内且在鸟下方)
		if (horizontalDist < 2.0 && toPlayer.y < 0) {
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

	/** 受惊起飞 */
	public void startFlight(Player source) {
		if (state == State.FLYING) {
			return;
		}
		state = State.FLYING;
		playSound(ModSounds.HERON_SCARED, 1.0F, 1.0F);
		// 朝远离玩家方向飞,目标点水平距离 35 格、海拔起飞点 + 6
		Vec3 away = position().subtract(source.position()).multiply(1, 0, 1).normalize();
		if (away.lengthSqr() < 0.001) {
			away = new Vec3(getRandom().nextFloat() * 2 - 1, 0, getRandom().nextFloat() * 2 - 1).normalize();
		}
		flyTarget = position().add(away.scale(FLY_AWAY_DISTANCE)).add(0, FLY_ALTITUDE, 0);
	}

	public void startFlightAway(Vec3 direction) {
		if (state == State.FLYING) {
			return;
		}
		state = State.FLYING;
		playSound(ModSounds.HERON_SCARED, 1.0F, 1.0F);
		Vec3 away = direction.multiply(1, 0, 1).normalize();
		if (away.lengthSqr() < 0.001) {
			away = new Vec3(1, 0, 0);
		}
		flyTarget = position().add(away.scale(FLY_AWAY_DISTANCE)).add(0, FLY_ALTITUDE, 0);
	}

	public boolean isFlying() {
		return state == State.FLYING;
	}

	public boolean isInScareCooldown() {
		return level().getGameTime() < scaredCooldownUntil;
	}

	/** 找到目标点下方的落地位置;无安全落点返回空 */
	private Optional<Vec3> findLandingSpot() {
		var clip = level().clip(new ClipContext(
			flyTarget.add(0, 1, 0),
			flyTarget.add(0, -8, 0),
			ClipContext.Block.COLLIDER,
			ClipContext.Fluid.NONE,
			this));
		if (clip.getType() == HitResult.Type.BLOCK) {
			Vec3 hit = clip.getLocation();
			if (level().isEmptyBlock(BlockPos.containing(hit))) {
				return Optional.of(hit);
			}
		}
		return Optional.empty();
	}

	/** 飞行推进(服务端 tick) */
	private void tickFlight() {
		Vec3 to = flyTarget.subtract(position());
		double horizontal = to.horizontalDistance();
		if (horizontal < 1.5) {
			// 到达:找落点降落
			Optional<Vec3> landing = findLandingSpot();
			if (landing.isPresent()) {
				Vec3 spot = landing.get();
				setPos(spot.x, spot.y, spot.z);
			} else {
				setPos(flyTarget.x, position().y, flyTarget.z);
			}
			setNoGravity(false);
			state = State.IDLE;
			scaredCooldownUntil = level().getGameTime() + SCARE_COOLDOWN_TICKS;
			callCooldown = 40;
			return;
		}
		Vec3 dir = to.normalize().scale(FLY_SPEED / 20.0);
		setDeltaMovement(dir.x, dir.y * 0.5 + 0.02, dir.z);
		// 面朝飞行方向
		setYRot((float) (Math.toDegrees(Math.atan2(-dir.x, dir.z))));
		setYHeadRot(getYRot());
	}

	@Override
	public void aiStep() {
		super.aiStep();
		if (!level().isClientSide() && state == State.FLYING) {
			tickFlight();
		}
	}

	// ------------------------------------------------------------------
	// Goals
	// ------------------------------------------------------------------

	/** 惊扰检测(最高优先级):距离 + 可见 → 起飞;受惊冷却内不触发 */
	static class HeronScareGoal extends Goal {
		private final HeronEntity heron;

		HeronScareGoal(HeronEntity heron) {
			this.heron = heron;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (heron.isFlying() || heron.isInScareCooldown()) {
				return false;
			}
			if (!(heron.level() instanceof ServerLevel serverLevel)) {
				return false;
			}
			for (Player player : serverLevel.players()) {
				if (player.isSpectator() || player.isCreative()) {
					continue;
				}
				double dist = heron.distanceToSqr(player);
				if (dist < heron.currentScareDistance(player) * heron.currentScareDistance(player)
					&& heron.canSeePlayer(player)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public void start() {
			if (!(heron.level() instanceof ServerLevel serverLevel)) {
				return;
			}
			Player nearest = null;
			double best = Double.MAX_VALUE;
			for (Player player : serverLevel.players()) {
				double d = heron.distanceToSqr(player);
				if (d < best) {
					best = d;
					nearest = player;
				}
			}
			if (nearest != null) {
				heron.startFlight(nearest);
			} else {
				heron.startFlightAway(Vec3.directionFromRotation(heron.getYRot(), 0));
			}
		}
	}

	/** 飞行推进(占位 Goal,实际推进在 aiStep;此处仅保证不落地行走) */
	static class HeronFlyGoal extends Goal {
		private final HeronEntity heron;

		HeronFlyGoal(HeronEntity heron) {
			this.heron = heron;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			return heron.isFlying();
		}

		@Override
		public boolean canContinueToUse() {
			return heron.isFlying();
		}

		@Override
		public void start() {
			heron.setNoGravity(true);
			heron.getNavigation().stop();
		}

		@Override
		public void stop() {
			heron.setNoGravity(false);
		}
	}

	/** 夜晚栖息:不动不觅食 */
	static class HeronSleepGoal extends Goal {
		private final HeronEntity heron;

		HeronSleepGoal(HeronEntity heron) {
			this.heron = heron;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (heron.isFlying()) {
				return false;
			}
			// 夜晚:overworld 时钟 13000~23000(黄昏后到黎明前)
			long time = heron.level().getOverworldClockTime() % 24000L;
			return time >= 13000 && time < 23000;
		}

		@Override
		public void start() {
			heron.state = State.SLEEPING;
			heron.getNavigation().stop();
		}

		@Override
		public void stop() {
			heron.state = State.IDLE;
		}
	}

	/** 觅食:走向就近随机点并停顿(低头动作 M2b 动画接入) */
	static class HeronForageGoal extends Goal {
		private final HeronEntity heron;
		private int restTicks;

		HeronForageGoal(HeronEntity heron) {
			this.heron = heron;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (heron.isFlying() || heron.getState() == State.SLEEPING) {
				return false;
			}
			return heron.getNavigation().isDone() && heron.getRandom().nextInt(60) == 0;
		}

		@Override
		public boolean canContinueToUse() {
			return !heron.isFlying() && (!heron.getNavigation().isDone() || restTicks > 0);
		}

		@Override
		public void start() {
			heron.state = State.FORAGING;
			restTicks = 60 + heron.getRandom().nextInt(100); // 到点后停顿 3~8 秒(低头觅食,M2b 动画)
			BlockPos target = heron.blockPosition().offset(
				heron.getRandom().nextInt(17) - 8, 0, heron.getRandom().nextInt(17) - 8);
			heron.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.5);
		}

		@Override
		public void tick() {
			if (heron.getNavigation().isDone()) {
				restTicks--;
			}
		}

		@Override
		public void stop() {
			heron.state = State.IDLE;
		}
	}

	/** 随机鸣叫(听声辨位音源):间隔 2~10 分钟(游戏内) */
	static class HeronAmbientCallGoal extends Goal {
		private final HeronEntity heron;

		HeronAmbientCallGoal(HeronEntity heron) {
			this.heron = heron;
		}

		@Override
		public boolean canUse() {
			if (heron.isFlying() || heron.getState() == State.SLEEPING) {
				return false;
			}
			if (--heron.callCooldown > 0) {
				return false;
			}
			heron.callCooldown = 2400 + heron.getRandom().nextInt(9600);
			return true;
		}

		@Override
		public void start() {
			heron.playSound(ModSounds.HERON_AMBIENT, 1.0F, 0.9F + heron.getRandom().nextFloat() * 0.3F);
		}
	}
}
