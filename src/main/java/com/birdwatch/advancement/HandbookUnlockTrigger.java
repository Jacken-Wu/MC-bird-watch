package com.birdwatch.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * 成就触发器:图鉴解锁(birdwatch:handbook_unlock)。
 * 印刷照片贴入图鉴解锁条目时触发(客户端 → C2S 授奖)。
 */
public class HandbookUnlockTrigger extends SimpleCriterionTrigger<HandbookUnlockTrigger.TriggerInstance> {
	public static final HandbookUnlockTrigger INSTANCE = new HandbookUnlockTrigger();

	@Override
	public Codec<TriggerInstance> codec() {
		return TriggerInstance.CODEC;
	}

	public void fire(ServerPlayer player, String species) {
		trigger(player, inst -> inst.species.isEmpty() || inst.species.equals(species));
	}

	public record TriggerInstance(
		Optional<ContextAwarePredicate> player,
		String species
	) implements SimpleInstance {
		// 26.2 原版写法:RecordCodecBuilder.create(...)(与 PlayerTrigger 等一致)
		public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(inst -> inst.group(
			ContextAwarePredicate.CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
			Codec.STRING.optionalFieldOf("species", "").forGetter(TriggerInstance::species)
		).apply(inst, TriggerInstance::new));

		@Override
		public Optional<ContextAwarePredicate> player() {
			return player;
		}
	}
}
