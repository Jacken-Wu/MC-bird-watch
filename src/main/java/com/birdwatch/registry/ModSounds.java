package com.birdwatch.registry;

import com.birdwatch.BirdWatchMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * 音效注册入口。
 *
 * 注意:sounds.json 中引用原版音效文件作占位素材(听声辨位机制先行),
 * M2b 采集真实鸟鸣后替换音频资源,代码无需改动。
 */
public final class ModSounds {
	/** 白鹭鸣叫(听声辨位音源) */
	public static final SoundEvent HERON_AMBIENT = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "heron_ambient"));
	/** 白鹭受惊叫 */
	public static final SoundEvent HERON_SCARED = SoundEvent.createVariableRangeEvent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "heron_scared"));

	public static void registerAll() {
		Registry.register(BuiltInRegistries.SOUND_EVENT, HERON_AMBIENT.location(), HERON_AMBIENT);
		Registry.register(BuiltInRegistries.SOUND_EVENT, HERON_SCARED.location(), HERON_SCARED);
	}

	private ModSounds() {
	}
}
