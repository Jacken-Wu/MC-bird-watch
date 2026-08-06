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

	public static void registerAll() {
		Registry.register(BuiltInRegistries.SOUND_EVENT, LITTLE_EGRET_AMBIENT.location(), LITTLE_EGRET_AMBIENT);
		Registry.register(BuiltInRegistries.SOUND_EVENT, LITTLE_EGRET_SCARED.location(), LITTLE_EGRET_SCARED);
		Registry.register(BuiltInRegistries.SOUND_EVENT, LITTLE_EGRET_HURT.location(), LITTLE_EGRET_HURT);
		Registry.register(BuiltInRegistries.SOUND_EVENT, LITTLE_EGRET_DEATH.location(), LITTLE_EGRET_DEATH);
		Registry.register(BuiltInRegistries.SOUND_EVENT, LITTLE_EGRET_FLAP.location(), LITTLE_EGRET_FLAP);
	}

	private ModSounds() {
	}
}
