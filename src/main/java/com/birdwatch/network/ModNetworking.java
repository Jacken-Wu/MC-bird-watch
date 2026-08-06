package com.birdwatch.network;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.advancement.HandbookUnlockTrigger;
import com.birdwatch.advancement.PhotoRatedTrigger;
import com.birdwatch.config.BirdWatchConfig;
import com.birdwatch.item.PhotoPrintItem;
import com.birdwatch.menu.CameraLensMenuHandler;
import com.birdwatch.registry.ModItems;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 网络协议注册。
 *
 * C2S OpenLensMenuPayload:客户端在"潜行+右键"时发送,服务端打开镜头槽菜单。
 * C2S PhotoRatedPayload / HandbookUnlockPayload:拍摄判定纯客户端(设计定稿),
 * 客户端评分/解锁后向服务端授奖(成就授予必须走服务端)。
 * C2S PrintRequestPayload:印刷物品必须服务端创建 —— 客户端直接 add 到本地背包
 * 是"幽灵物品"(服务端不知情),生存模式点击一次即被同步清掉(踩坑记录)。
 */
public final class ModNetworking {
	public static final CustomPacketPayload.Type<OpenLensMenuPayload> OPEN_LENS_MENU =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "open_lens_menu"));
	public static final CustomPacketPayload.Type<PhotoRatedPayload> PHOTO_RATED =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "photo_rated"));
	public static final CustomPacketPayload.Type<HandbookUnlockPayload> HANDBOOK_UNLOCK =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "handbook_unlock"));
	public static final CustomPacketPayload.Type<PrintRequestPayload> PRINT_REQUEST =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "print_request"));

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

	/**
	 * 图鉴解锁授奖(species + 新照片路径 + 旧照片路径/裁剪)。
	 * 服务端负责:旧照片返还物品、消耗新照片物品(客户端直接改背包会幽灵化)。
	 */
	public record HandbookUnlockPayload(String species, String newPhoto, String oldPhoto, String oldCrop)
		implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, HandbookUnlockPayload> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, HandbookUnlockPayload::species,
				ByteBufCodecs.STRING_UTF8, HandbookUnlockPayload::newPhoto,
				ByteBufCodecs.STRING_UTF8, HandbookUnlockPayload::oldPhoto,
				ByteBufCodecs.STRING_UTF8, HandbookUnlockPayload::oldCrop,
				HandbookUnlockPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return HANDBOOK_UNLOCK;
		}
	}

	/** 印刷请求:服务端消耗 1 张纸并创建印刷照片物品入包 */
	public record PrintRequestPayload(String photoPath, String species, int score, String tier, String crop)
		implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, PrintRequestPayload> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, PrintRequestPayload::photoPath,
				ByteBufCodecs.STRING_UTF8, PrintRequestPayload::species,
				ByteBufCodecs.VAR_INT, PrintRequestPayload::score,
				ByteBufCodecs.STRING_UTF8, PrintRequestPayload::tier,
				ByteBufCodecs.STRING_UTF8, PrintRequestPayload::crop,
				PrintRequestPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return PRINT_REQUEST;
		}
	}

	/** 创建印刷照片物品(服务端/客户端共用) */
	public static ItemStack createPrintItem(String photoPath, String species, int score, String tier, String crop) {
		ItemStack print = new ItemStack(ModItems.PHOTO_PRINT);
		CustomData.update(DataComponents.CUSTOM_DATA, print, tag -> {
			tag.putString(PhotoPrintItem.KEY_PHOTO, photoPath);
			tag.putString(PhotoPrintItem.KEY_SPECIES, species);
			tag.putInt(PhotoPrintItem.KEY_SCORE, score);
			tag.putString(PhotoPrintItem.KEY_TIER, tier);
			tag.putString(PhotoPrintItem.KEY_CROP, crop);
		});
		return print;
	}

	/** 服务端:背包中找指定照片路径的印刷物品(图鉴贴入消耗用) */
	private static ItemStack findPrint(ServerPlayer player, String photoPath) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.is(ModItems.PHOTO_PRINT)) {
				continue;
			}
			var data = stack.get(DataComponents.CUSTOM_DATA);
			if (data == null) {
				continue;
			}
			if (photoPath.equals(data.copyTag().getString(PhotoPrintItem.KEY_PHOTO).orElse(""))) {
				return stack;
			}
		}
		return ItemStack.EMPTY;
	}

	/** 服务端:从照片元数据 JSON 读评分(旧照片返还用);读取失败返回 0 */
	private static int readScoreFromJson(ServerPlayer player, String photoPath) {
		try {
			Path root = player.level().getServer().getWorldPath(LevelResource.ROOT)
				.resolve(BirdWatchConfig.photoDirectory);
			Path json = root.resolve(photoPath).resolveSibling(
				java.nio.file.Path.of(photoPath).getFileName().toString().replace(".png", ".json"));
			if (Files.exists(json)) {
				var obj = com.google.gson.JsonParser.parseString(Files.readString(json))
					.getAsJsonObject();
				if (obj.has("birds") && obj.getAsJsonArray("birds").size() > 0) {
					return obj.getAsJsonArray("birds").get(0).getAsJsonObject().get("score").getAsInt();
				}
			}
		} catch (Exception e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 读取照片元数据失败 {}", photoPath, e);
		}
		return 0;
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
			context.server().execute(() -> {
				// 旧照片返还(替换槽位时)
				if (!payload.oldPhoto().isEmpty() && !payload.oldPhoto().equals(payload.newPhoto())) {
					ItemStack oldPrint = createPrintItem(payload.oldPhoto(),
						payload.species(), readScoreFromJson(player, payload.oldPhoto()),
						"", payload.oldCrop());
					if (!player.getInventory().add(oldPrint)) {
						player.drop(oldPrint, false);
					}
				}
				// 消耗新照片物品(服务端,防幽灵)
				ItemStack newPrint = findPrint(player, payload.newPhoto());
				if (!newPrint.isEmpty()) {
					newPrint.shrink(1);
				}
				HandbookUnlockTrigger.INSTANCE.fire(player, payload.species());
			});
		});

		PayloadTypeRegistry.serverboundPlay().register(PRINT_REQUEST, PrintRequestPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(PRINT_REQUEST, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				// 消耗 1 张纸
				boolean consumed = false;
				for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
					ItemStack stack = player.getInventory().getItem(i);
					if (stack.is(Items.PAPER)) {
						stack.shrink(1);
						consumed = true;
						break;
					}
				}
				if (!consumed) {
					return;
				}
				// 服务端创建印刷照片入包
				ItemStack print = createPrintItem(payload.photoPath(), payload.species(),
					payload.score(), payload.tier(), payload.crop());
				if (!player.getInventory().add(print)) {
					player.drop(print, false);
				}
			});
		});
	}

	private ModNetworking() {
	}
}
