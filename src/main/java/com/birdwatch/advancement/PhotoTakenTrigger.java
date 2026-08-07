package com.birdwatch.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * 成就触发器:任意拍照(birdwatch:photo_taken)。
 * 纯客户端判定 → C2S 授奖包 → 服务端 trigger(休闲定位,无防作弊)。
 * 无评分条件:拍下任意一张照片(含风景/生物)即达成 —— 「第一次拍照」成就。
 */
public class PhotoTakenTrigger extends SimpleCriterionTrigger<PhotoTakenTrigger.TriggerInstance> {
	public static final PhotoTakenTrigger INSTANCE = new PhotoTakenTrigger();

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
