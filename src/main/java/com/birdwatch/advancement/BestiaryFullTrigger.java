package com.birdwatch.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * 成就触发器:生物图鉴全解锁(birdwatch:bestiary_full)。
 * 服务端 BestiaryProgress 在解锁时判断收录数 == 全部 → fire(无附加条件)。
 */
public class BestiaryFullTrigger extends SimpleCriterionTrigger<BestiaryFullTrigger.TriggerInstance> {
	public static final BestiaryFullTrigger INSTANCE = new BestiaryFullTrigger();

	@Override
	public Codec<TriggerInstance> codec() {
		return TriggerInstance.CODEC;
	}

	public void fire(ServerPlayer player) {
		trigger(player, inst -> true);
	}

	public record TriggerInstance(Optional<ContextAwarePredicate> player) implements SimpleInstance {
		public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(inst -> inst.group(
			ContextAwarePredicate.CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player)
		).apply(inst, TriggerInstance::new));

		@Override
		public Optional<ContextAwarePredicate> player() {
			return player;
		}
	}
}
