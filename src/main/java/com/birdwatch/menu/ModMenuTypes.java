package com.birdwatch.menu;

import com.birdwatch.BirdWatchMod;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.MenuType;

/**
 * 菜单类型注册(相机镜头槽容器)。
 * 26.2 的 MenuType 构造器为 private,使用 fabric 的 ExtendedMenuType(公共构造器)。
 */
public final class ModMenuTypes {
	/**
	 * 空数据的占位实例。open_screen 编码器不允许 null(对 null 调 equals 抛 NPE,
	 * 会导致连接被断),codec 与 ExtendedMenuProvider.getScreenOpeningData 必须返回同一实例。
	 */
	public static final Object NO_DATA = new Object();

	public static final MenuType<CameraLensMenuHandler> CAMERA_LENS_MENU =
		new ExtendedMenuType<>((syncId, inv, data) -> new CameraLensMenuHandler(syncId, inv),
			StreamCodec.unit(NO_DATA));

	public static void register() {
		Registry.register(BuiltInRegistries.MENU,
			Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "camera_lens"),
			CAMERA_LENS_MENU);
	}

	private ModMenuTypes() {
	}
}
