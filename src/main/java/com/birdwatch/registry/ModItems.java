package com.birdwatch.registry;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.camera.LensRegistry;
import com.birdwatch.item.BestiaryItem;
import com.birdwatch.item.CameraItem;
import com.birdwatch.item.PhotoPrintItem;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 物品注册入口。
 *
 * 注意:26.2 使用官方映射 —— 物品构造必须 setId,否则 "Item id not set" 崩溃。
 */
public final class ModItems {
	public static final ResourceKey<CreativeModeTab> BIRDWATCH_GROUP_KEY =
		ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "birdwatch"));

	/** 相机 —— M1:取景器/镜头槽;后续里程碑接入拍摄玩法 */
	public static final Item CAMERA = new CameraItem(new Item.Properties()
		.setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "camera")))
		.stacksTo(1));

	/** 镜头(由 LensRegistry 定义数据,物品仅作载体) */
	public static final Item LENS_24MM = lens("lens_24mm");
	public static final Item LENS_50MM = lens("lens_50mm");
	public static final Item LENS_200MM = lens("lens_200mm");
	public static final Item LENS_400MM = lens("lens_400mm");
	public static final Item LENS_ZOOM_70_300 = lens("lens_zoom_70_300");

	/** 观鸟图鉴 —— 右键打开图鉴界面(开局自带) */
	public static final Item HANDBOOK = new Item(new Item.Properties()
		.setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "handbook")))
		.stacksTo(1));

	/** 生物图鉴 —— 右键打开原版生物图鉴界面(拍照解锁,开局自带) */
	public static final Item BESTIARY = new BestiaryItem(new Item.Properties()
		.setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "bestiary")))
		.stacksTo(1));

	/** 印刷照片 —— 相册印刷(可裁剪)产出,贴入图鉴解锁鸟种 */
	public static final Item PHOTO_PRINT = new PhotoPrintItem(new Item.Properties()
		.setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "photo_print"))));

	public static final CreativeModeTab BIRDWATCH_GROUP = FabricCreativeModeTab.builder()
		.title(Component.translatable("itemGroup.birdwatch.birdwatch"))
		.icon(() -> new ItemStack(CAMERA))
		.displayItems((displayContext, entries) -> {
			entries.accept(CAMERA);
			LensRegistry.LENSES.forEach(def -> entries.accept(BuiltInRegistries.ITEM.getValue(
				Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, def.id()))));
			entries.accept(HANDBOOK);
			entries.accept(BESTIARY);
		})
		.build();

	public static void registerAll() {
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "camera"), CAMERA);
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "lens_24mm"), LENS_24MM);
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "lens_50mm"), LENS_50MM);
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "lens_200mm"), LENS_200MM);
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "lens_400mm"), LENS_400MM);
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "lens_zoom_70_300"), LENS_ZOOM_70_300);
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "handbook"), HANDBOOK);
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "bestiary"), BESTIARY);
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "photo_print"), PHOTO_PRINT);
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, BIRDWATCH_GROUP_KEY, BIRDWATCH_GROUP);
	}

	private static Item lens(String id) {
		return new Item(new Item.Properties()
			.setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, id)))
			.stacksTo(1));
	}

	private ModItems() {
	}
}
