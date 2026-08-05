package com.birdwatch.network;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.menu.CameraLensMenuHandler;
import com.birdwatch.registry.ModItems;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络协议注册。
 *
 * C2S OpenLensMenuPayload:客户端在"潜行+右键"时发送,服务端打开镜头槽菜单。
 * 潜行由客户端判定(原始输入轮询,权威);服务端 isShiftKeyDown 依赖移动包同步潜行标志,
 * 时机不可靠 —— 这是镜头槽打不开的根因。
 */
public final class ModNetworking {
	public static final CustomPacketPayload.Type<OpenLensMenuPayload> OPEN_LENS_MENU =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "open_lens_menu"));

	public record OpenLensMenuPayload() implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenLensMenuPayload> CODEC =
			StreamCodec.unit(new OpenLensMenuPayload());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return OPEN_LENS_MENU;
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
	}

	private ModNetworking() {
	}
}
