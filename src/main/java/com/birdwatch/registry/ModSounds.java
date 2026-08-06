package com.birdwatch.registry;

import com.birdwatch.BirdWatchMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * 音效注册入口。
 *
 * M2b 起使用本地程序化合成的白鹭音效
 * (assets/birdwatch/sounds/little_egret/*.ogg,由 tools/gen_sounds.py 生成)。
 * 麻雀/山雀(M4a 验证物种)音效事件已注册,资源暂指向白鹭 ogg(sounds.json 占位),
 * 真实录音采集在 M6 替换。
 */
public final class ModSounds {
	/** 小白鹭鸣叫(听声辨位音源) */
	public static final SoundEvent LITTLE_EGRET_AMBIENT = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "little_egret_ambient"));
	/** 小白鹭受惊叫(起飞时) */
	public static final SoundEvent LITTLE_EGRET_SCARED = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "little_egret_scared"));
	/** 小白鹭受伤 */
	public static final SoundEvent LITTLE_EGRET_HURT = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "little_egret_hurt"));
	/** 小白鹭死亡 */
	public static final SoundEvent LITTLE_EGRET_DEATH = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "little_egret_death"));
	/** 小白鹭扇翅(飞行中周期性触发) */
	public static final SoundEvent LITTLE_EGRET_FLAP = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "little_egret_flap"));

	/** 麻雀鸣叫 */
	public static final SoundEvent SPARROW_AMBIENT = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "sparrow_ambient"));
	/** 麻雀受惊叫(起飞时) */
	public static final SoundEvent SPARROW_SCARED = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "sparrow_scared"));
	/** 麻雀受伤 */
	public static final SoundEvent SPARROW_HURT = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "sparrow_hurt"));
	/** 麻雀死亡 */
	public static final SoundEvent SPARROW_DEATH = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "sparrow_death"));
	/** 麻雀扇翅 */
	public static final SoundEvent SPARROW_FLAP = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "sparrow_flap"));

	/** 山雀鸣叫 */
	public static final SoundEvent TIT_AMBIENT = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "tit_ambient"));
	/** 山雀受惊叫(起飞时) */
	public static final SoundEvent TIT_SCARED = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "tit_scared"));
	/** 山雀受伤 */
	public static final SoundEvent TIT_HURT = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "tit_hurt"));
	/** 山雀死亡 */
	public static final SoundEvent TIT_DEATH = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "tit_death"));
	/** 山雀扇翅 */
	public static final SoundEvent TIT_FLAP = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "tit_flap"));

	public static void registerAll() {
		Registry.register(BuiltInRegistries.SOUND_EVENT, LITTLE_EGRET_AMBIENT.location(), LITTLE_EGRET_AMBIENT);
		Registry.register(BuiltInRegistries.SOUND_EVENT, LITTLE_EGRET_SCARED.location(), LITTLE_EGRET_SCARED);
		Registry.register(BuiltInRegistries.SOUND_EVENT, LITTLE_EGRET_HURT.location(), LITTLE_EGRET_HURT);
		Registry.register(BuiltInRegistries.SOUND_EVENT, LITTLE_EGRET_DEATH.location(), LITTLE_EGRET_DEATH);
		Registry.register(BuiltInRegistries.SOUND_EVENT, LITTLE_EGRET_FLAP.location(), LITTLE_EGRET_FLAP);
		Registry.register(BuiltInRegistries.SOUND_EVENT, SPARROW_AMBIENT.location(), SPARROW_AMBIENT);
		Registry.register(BuiltInRegistries.SOUND_EVENT, SPARROW_SCARED.location(), SPARROW_SCARED);
		Registry.register(BuiltInRegistries.SOUND_EVENT, SPARROW_HURT.location(), SPARROW_HURT);
		Registry.register(BuiltInRegistries.SOUND_EVENT, SPARROW_DEATH.location(), SPARROW_DEATH);
		Registry.register(BuiltInRegistries.SOUND_EVENT, SPARROW_FLAP.location(), SPARROW_FLAP);
		Registry.register(BuiltInRegistries.SOUND_EVENT, TIT_AMBIENT.location(), TIT_AMBIENT);
		Registry.register(BuiltInRegistries.SOUND_EVENT, TIT_SCARED.location(), TIT_SCARED);
		Registry.register(BuiltInRegistries.SOUND_EVENT, TIT_HURT.location(), TIT_HURT);
		Registry.register(BuiltInRegistries.SOUND_EVENT, TIT_DEATH.location(), TIT_DEATH);
		Registry.register(BuiltInRegistries.SOUND_EVENT, TIT_FLAP.location(), TIT_FLAP);
	}

	private ModSounds() {
	}
}
