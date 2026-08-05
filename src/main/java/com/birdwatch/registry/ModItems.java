package com.birdwatch.registry;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.camera.LensRegistry;
import com.birdwatch.item.CameraItem;
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

	public static final CreativeModeTab BIRDWATCH_GROUP = FabricCreativeModeTab.builder()
		.title(Component.translatable("itemGroup.birdwatch.birdwatch"))
		.icon(() -> new ItemStack(CAMERA))
		.displayItems((displayContext, entries) -> {
			entries.accept(CAMERA);
			LensRegistry.LENSES.forEach(def -> entries.accept(BuiltInRegistries.ITEM.getValue(
				Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, def.id()))));
		})
		.build();

	public static void registerAll() {
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "camera"), CAMERA);
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "lens_24mm"), LENS_24MM);
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "lens_50mm"), LENS_50MM);
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "lens_200mm"), LENS_200MM);
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "lens_400mm"), LENS_400MM);
		Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "lens_zoom_70_300"), LENS_ZOOM_70_300);
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
