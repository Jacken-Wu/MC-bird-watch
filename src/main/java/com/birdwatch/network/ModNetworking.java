package com.birdwatch.network;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.advancement.HandbookUnlockTrigger;
import com.birdwatch.advancement.PhotoRatedTrigger;
import com.birdwatch.config.BirdWatchConfig;
import com.birdwatch.item.PhotoPrintItem;
import com.birdwatch.menu.CameraLensMenuHandler;
import com.birdwatch.registry.ModItems;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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

import java.util.List;

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
	/** 生物图鉴拍照解锁(原版生物) */
	public static final CustomPacketPayload.Type<BestiaryUnlockPayload> BESTIARY_UNLOCK =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "bestiary_unlock"));
	public static final CustomPacketPayload.Type<PrintRequestPayload> PRINT_REQUEST =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "print_request"));
	/** 印刷图主动下发(图鉴贴入时给贴入玩家) */
	public static final CustomPacketPayload.Type<PrintImagePayload> PRINT_IMAGE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "print_image"));
	/** 印刷图按需请求(客户端渲染缓存缺失) */
	public static final CustomPacketPayload.Type<PrintImageRequestPayload> PRINT_IMAGE_REQUEST =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "print_image_request"));
	/** 印刷图删除通知(服务端文件已删 → 客户端同步清 print_cache) */
	public static final CustomPacketPayload.Type<PrintImageDeletePayload> PRINT_IMAGE_DELETE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "print_image_delete"));
	/** 生物图鉴状态查询(客户端打开图鉴时) */
	public static final CustomPacketPayload.Type<BestiaryQueryPayload> BESTIARY_QUERY =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "bestiary_query"));
	/** 任意拍照通知(第一次拍照成就) */
	public static final CustomPacketPayload.Type<PhotoTakenPayload> PHOTO_TAKEN =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "photo_taken"));
	/** 生物图鉴状态回传(已解锁生物 id 集合) */
	public static final CustomPacketPayload.Type<BestiaryStatePayload> BESTIARY_STATE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "bestiary_state"));

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

	/** 任意拍照通知(空载荷,每次按下快门发送) */
	public record PhotoTakenPayload() implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, PhotoTakenPayload> CODEC =
			StreamCodec.unit(new PhotoTakenPayload());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return PHOTO_TAKEN;
		}
	}

	/** 生物图鉴状态查询(空载荷,客户端打开图鉴时发送) */
	public record BestiaryQueryPayload() implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, BestiaryQueryPayload> CODEC =
			StreamCodec.unit(new BestiaryQueryPayload());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return BESTIARY_QUERY;
		}
	}

	/** 生物图鉴状态回传:已解锁生物 id 列表 */
	public record BestiaryStatePayload(List<String> unlocked) implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, BestiaryStatePayload> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8.apply(net.minecraft.network.codec.ByteBufCodecs.collection(
					java.util.ArrayList::new)), BestiaryStatePayload::unlocked,
				BestiaryStatePayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return BESTIARY_STATE;
		}
	}

	/** 生物图鉴拍照解锁(原版生物 entity id) */
	public record BestiaryUnlockPayload(String entityId) implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, BestiaryUnlockPayload> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, BestiaryUnlockPayload::entityId,
				BestiaryUnlockPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return BESTIARY_UNLOCK;
		}
	}

	/**
	 * 图鉴解锁授奖(species + 新印刷图 printId + 旧 printId/裁剪)。
	 * 服务端负责:消耗新照片物品并删除其印刷图文件、旧照片返还物品(引用转移,文件保留)、
	 * 主动下发新印刷图字节给贴入玩家(槽位渲染立即可用)。客户端直接改背包会幽灵化。
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

	/** 印刷请求:上传缩放后的印刷图字节,服务端消耗 1 张纸、写印刷图文件并创建物品入包 */
	public record PrintRequestPayload(byte[] pngBytes, String species, int score, String tier, String crop,
		String birdsJson) implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, PrintRequestPayload> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.BYTE_ARRAY, PrintRequestPayload::pngBytes,
				ByteBufCodecs.STRING_UTF8, PrintRequestPayload::species,
				ByteBufCodecs.VAR_INT, PrintRequestPayload::score,
				ByteBufCodecs.STRING_UTF8, PrintRequestPayload::tier,
				ByteBufCodecs.STRING_UTF8, PrintRequestPayload::crop,
				ByteBufCodecs.STRING_UTF8, PrintRequestPayload::birdsJson,
				PrintRequestPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return PRINT_REQUEST;
		}
	}

	/** 印刷图下发:服务端 → 客户端(printId + PNG 字节,写客户端缓存) */
	public record PrintImagePayload(String printId, byte[] pngBytes) implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, PrintImagePayload> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, PrintImagePayload::printId,
				ByteBufCodecs.BYTE_ARRAY, PrintImagePayload::pngBytes,
				PrintImagePayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return PRINT_IMAGE;
		}
	}

	/** 印刷图请求:客户端渲染缓存缺失 → 按需拉取 */
	public record PrintImageRequestPayload(String printId) implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, PrintImageRequestPayload> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, PrintImageRequestPayload::printId,
				PrintImageRequestPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return PRINT_IMAGE_REQUEST;
		}
	}

	/** 印刷图删除通知:服务端文件已删(物品销毁/图鉴消耗),客户端应清对应 print_cache */
	public record PrintImageDeletePayload(String printId) implements CustomPacketPayload {
		public static final StreamCodec<RegistryFriendlyByteBuf, PrintImageDeletePayload> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, PrintImageDeletePayload::printId,
				PrintImageDeletePayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return PRINT_IMAGE_DELETE;
		}
	}

	/**
	 * 广播印刷图删除通知给所有在线玩家(服务端文件已删 → 客户端清对应 print_cache)。
	 * 物品销毁/图鉴消耗等明确删除路径调用;周期 GC 删除不广播(文件可能长期无引用)。
	 */
	public static void broadcastPrintImageDelete(net.minecraft.server.MinecraftServer server, String printId) {
		PrintImageDeletePayload payload = new PrintImageDeletePayload(printId);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(p, payload);
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

	/** 服务端:从印刷图元数据 JSON 读评分(旧照片返还用);读取失败返回 0 */
	private static int readScoreFromJson(ServerPlayer player, String printId) {
		return com.birdwatch.print.PrintStore.readScore(player.level().getServer(), printId);
	}

	public static void register() {
		// 服务端 → 客户端:印刷图下发 / 删除通知 / 生物图鉴状态
		PayloadTypeRegistry.clientboundPlay().register(PRINT_IMAGE, PrintImagePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PRINT_IMAGE_DELETE, PrintImageDeletePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BESTIARY_STATE, BestiaryStatePayload.CODEC);
		// 印刷图周期 GC:每 10 分钟清理无引用且超龄的印刷图文件(防存档膨胀)
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 12000 == 0 && server.overworld() != null) {
				com.birdwatch.print.PrintStore.gc(server.overworld());
			}
		});
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

		PayloadTypeRegistry.serverboundPlay().register(PHOTO_TAKEN, PhotoTakenPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(PHOTO_TAKEN, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() ->
				com.birdwatch.advancement.PhotoTakenTrigger.INSTANCE.fire(player));
		});

		PayloadTypeRegistry.serverboundPlay().register(BESTIARY_UNLOCK, BestiaryUnlockPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(BESTIARY_UNLOCK, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() ->
				com.birdwatch.event.BestiaryProgress.unlock(player, payload.entityId()));
		});

		// 生物图鉴状态查询:客户端打开图鉴 → 服务端回传已解锁集合
		PayloadTypeRegistry.serverboundPlay().register(BESTIARY_QUERY, BestiaryQueryPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(BESTIARY_QUERY, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> ServerPlayNetworking.send(player,
				new BestiaryStatePayload(java.util.List.copyOf(
					com.birdwatch.event.BestiaryProgress.unlockedOf(player)))));
		});

		PayloadTypeRegistry.serverboundPlay().register(HANDBOOK_UNLOCK, HandbookUnlockPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(HANDBOOK_UNLOCK, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				// 旧照片返还(更换槽位时):从槽位引用表取旧 printId,引用转移给返还物品,
				// 印刷图文件保留(槽位不再持有,由返还物品持有)。
				// 裁剪已烘焙进印刷图文件,返还物品 crop 恒为全图 "0,0,1,1"
				String oldPrintId = com.birdwatch.print.PrintStore.unbindSlot(context.server(), payload.species());
				if (oldPrintId != null && !oldPrintId.equals(payload.newPhoto())) {
					ItemStack oldPrint = createPrintItem(oldPrintId,
						payload.species(), readScoreFromJson(player, oldPrintId),
						"", "0,0,1,1");
					if (!player.getInventory().add(oldPrint)) {
						player.drop(oldPrint, false);
					}
				}
				// 消耗新照片物品(服务端,防幽灵);印刷图文件不删 —— 槽位登记引用持有
				ItemStack newPrint = findPrint(player, payload.newPhoto());
				if (!newPrint.isEmpty()) {
					newPrint.shrink(1);
				}
				com.birdwatch.print.PrintStore.bindSlot(context.server(), payload.species(), payload.newPhoto());
				// 主动下发新印刷图给贴入玩家(槽位渲染立即可用,无需再请求)
				com.birdwatch.print.PrintStore.readPng(context.server(), payload.newPhoto())
					.ifPresent(bytes -> ServerPlayNetworking.send(player,
						new PrintImagePayload(payload.newPhoto(), bytes)));
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
				// 服务端写印刷图文件并创建印刷照片物品入包(photo=printId)
				String printId = com.birdwatch.print.PrintStore.save(context.server(),
					payload.pngBytes(), payload.species(), payload.score(), payload.tier(), payload.birdsJson());
				if (printId == null) {
					BirdWatchMod.LOGGER.error("[Print] 印刷图写入失败,物品未创建");
					return;
				}
				ItemStack print = createPrintItem(printId, payload.species(),
					payload.score(), payload.tier(), payload.crop());
				if (!player.getInventory().add(print)) {
					player.drop(print, false);
				}
				// 主动下发印刷图给印刷者:物品入包后 GUI 图标首次渲染需要缓存已就绪,
				// 否则 atlas 槽位烘焙成空白且不再重绘(物品栏无缩略图,踩坑记录)
				ServerPlayNetworking.send(player, new PrintImagePayload(printId, payload.pngBytes()));
			});
		});

		// 印刷图按需请求:客户端渲染缓存缺失 → 服务端读文件回传
		PayloadTypeRegistry.serverboundPlay().register(PRINT_IMAGE_REQUEST, PrintImageRequestPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(PRINT_IMAGE_REQUEST, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() ->
				com.birdwatch.print.PrintStore.readPng(context.server(), payload.printId())
					.ifPresent(bytes -> ServerPlayNetworking.send(player,
						new PrintImagePayload(payload.printId(), bytes))));
		});
	}

	private ModNetworking() {
	}
}
