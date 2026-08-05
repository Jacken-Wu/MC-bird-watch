package com.birdwatch.network;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.advancement.HandbookUnlockTrigger;
import com.birdwatch.advancement.PhotoRatedTrigger;
import com.birdwatch.menu.CameraLensMenuHandler;
import com.birdwatch.registry.ModItems;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络协议注册。
 *
 * C2S OpenLensMenuPayload:客户端在"潜行+右键"时发送,服务端打开镜头槽菜单。
 * C2S PhotoRatedPayload / HandbookUnlockPayload:拍摄判定纯客户端(设计定稿),
 * 客户端评分/解锁后向服务端授奖(成就授予必须走服务端)。
 */
public final class ModNetworking {
	public static final CustomPacketPayload.Type<OpenLensMenuPayload> OPEN_LENS_MENU =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "open_lens_menu"));
	public static final CustomPacketPayload.Type<PhotoRatedPayload> PHOTO_RATED =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "photo_rated"));
	public static final CustomPacketPayload.Type<HandbookUnlockPayload> HANDBOOK_UNLOCK =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "handbook_unlock"));

	public record OpenLensMenuPayload() implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenLensMenuPayload> CODEC =
			StreamCodec.unit(new OpenLensMenuPayload());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return OPEN_LENS_MENU;
		}
	}

	/** 拍照评分授奖(species + score;休闲定位信任客户端分数) */
	public record PhotoRatedPayload(String species, int score) implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, PhotoRatedPayload> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, PhotoRatedPayload::species,
				ByteBufCodecs.VAR_INT, PhotoRatedPayload::score,
				PhotoRatedPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return PHOTO_RATED;
		}
	}

	/** 图鉴解锁授奖(species) */
	public record HandbookUnlockPayload(String species) implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, HandbookUnlockPayload> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, HandbookUnlockPayload::species,
				HandbookUnlockPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return HANDBOOK_UNLOCK;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(OPEN_LENS_MENU, OpenLensMenuPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(OPEN_LENS_MENU, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				if (player.getMainHandItem().is(ModItems.CAMERA)) {
					CameraLensMenuHandler.open(player);
				}
			});
		});

		PayloadTypeRegistry.serverboundPlay().register(PHOTO_RATED, PhotoRatedPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(PHOTO_RATED, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() ->
				PhotoRatedTrigger.INSTANCE.fire(player, payload.species(), payload.score()));
		});

		PayloadTypeRegistry.serverboundPlay().register(HANDBOOK_UNLOCK, HandbookUnlockPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(HANDBOOK_UNLOCK, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() ->
				HandbookUnlockTrigger.INSTANCE.fire(player, payload.species()));
		});
	}

	private ModNetworking() {
	}
}
