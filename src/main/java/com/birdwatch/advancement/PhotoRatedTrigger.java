package com.birdwatch.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * 成就触发器:拍照评分(birdwatch:photo_rated)。
 * 纯客户端判定 → C2S 授奖包 → 服务端 trigger(休闲定位,无防作弊)。
 *
 * 条件:species(空 = 任意物种)+ minScore;评分 ≥ minScore 即达成。
 */
public class PhotoRatedTrigger extends SimpleCriterionTrigger<PhotoRatedTrigger.TriggerInstance> {
	public static final PhotoRatedTrigger INSTANCE = new PhotoRatedTrigger();

	@Override
	public Codec<TriggerInstance> codec() {
		return TriggerInstance.CODEC;
	}

	public void fire(ServerPlayer player, String species, int score) {
		trigger(player, inst -> (inst.species.isEmpty() || inst.species.equals(species)) && score >= inst.minScore);
	}

	public record TriggerInstance(
		Optional<ContextAwarePredicate> player,
		String species,
		int minScore
	) implements SimpleInstance {
		// 26.2 原版写法:RecordCodecBuilder.create(...)(与 PlayerTrigger 等一致)
		public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(inst -> inst.group(
			ContextAwarePredicate.CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
			Codec.STRING.optionalFieldOf("species", "").forGetter(TriggerInstance::species),
			Codec.INT.optionalFieldOf("minScore", 60).forGetter(TriggerInstance::minScore)
		).apply(inst, TriggerInstance::new));

		@Override
		public Optional<ContextAwarePredicate> player() {
			return player;
		}
	}
}
