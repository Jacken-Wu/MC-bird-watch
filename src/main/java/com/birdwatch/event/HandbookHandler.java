package com.birdwatch.event;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.registry.ModItems;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * 开局自带观鸟图鉴:玩家首次进入世界时给一本(Data Attachment 持久化标记,只给一次)。
 */
public final class HandbookHandler {
	/** 已发放标记(随玩家数据持久化) */
	private static final AttachmentType<Boolean> HANDBOOK_GIVEN = AttachmentRegistry.createPersistent(
		Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "handbook_given"), Codec.BOOL);

	public static void register() {
		ServerPlayerEvents.JOIN.register(player -> {
			AttachmentTarget target = (AttachmentTarget) player;
			if (!Boolean.TRUE.equals(target.getAttached(HANDBOOK_GIVEN))) {
				target.setAttached(HANDBOOK_GIVEN, true);
				player.getInventory().add(new ItemStack(ModItems.HANDBOOK));
				BirdWatchMod.LOGGER.info("[BirdWatch] 已发放观鸟图鉴给 {}", player.getName().getString());
			}
		});
	}

	private HandbookHandler() {
	}
}
