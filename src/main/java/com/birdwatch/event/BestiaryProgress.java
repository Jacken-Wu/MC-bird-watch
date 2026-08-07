package com.birdwatch.event;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.bird.BestiaryRegistry;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 生物图鉴进度(服务端,随玩家数据持久化):
 * 已解锁的原版生物 entity id 集合,拍照解锁(C2S BestiaryUnlockPayload)写入。
 * 服务端为权威数据;客户端 BestiaryScreen 通过同步包获取解锁状态。
 */
public final class BestiaryProgress {
	/** 已解锁生物 id 集合(持久化附件) */
	private static final AttachmentType<Set<String>> UNLOCKED = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "bestiary_unlocked"),
		Codec.STRING.listOf().xmap(HashSet::new, List::copyOf));

	private BestiaryProgress() {
	}

	/** 拍照解锁:仅接受生物图鉴清单内的 id;重复解锁幂等;集齐全部 → 全图鉴成就 */
	public static void unlock(ServerPlayer player, String entityId) {
		if (BestiaryRegistry.typeOf(entityId).isEmpty()) {
			BirdWatchMod.LOGGER.debug("[BirdWatch] 生物图鉴:忽略未知实体 {}", entityId);
			return;
		}
		AttachmentTarget target = (AttachmentTarget) player;
		Set<String> unlocked = target.getAttached(UNLOCKED);
		if (unlocked == null) {
			unlocked = new HashSet<>();
			target.setAttached(UNLOCKED, unlocked);
		}
		if (unlocked.add(entityId)) {
			BirdWatchMod.LOGGER.info("[BirdWatch] {} 解锁生物图鉴条目 {}", player.getName().getString(), entityId);
			// 全部收录 → 全生物图鉴成就(服务端触发器去重)
			if (unlocked.size() >= BestiaryRegistry.allIds().size()) {
				com.birdwatch.advancement.BestiaryFullTrigger.INSTANCE.fire(player);
			}
		}
	}

	/** 玩家已解锁的生物 id 集合(空集安全) */
	public static Set<String> unlockedOf(ServerPlayer player) {
		Set<String> unlocked = ((AttachmentTarget) player).getAttached(UNLOCKED);
		return unlocked != null ? unlocked : Set.of();
	}
}
