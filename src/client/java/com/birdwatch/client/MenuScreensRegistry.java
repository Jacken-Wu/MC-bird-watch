package com.birdwatch.client;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.menu.CameraLensMenuHandler;
import com.birdwatch.menu.ModMenuTypes;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * 将相机镜头槽屏幕注册进 MenuScreens 的 SCREENS 表。
 * MenuScreens.register 为 private、ScreenConstructor 为包私有接口,
 * 用反射 + 动态代理注册(在客户端初始化时执行,此时 vanilla 已完成自注册)。
 */
public final class MenuScreensRegistry {
	private MenuScreensRegistry() {
	}

	public static void register() {
		try {
			java.lang.reflect.Field field = MenuScreens.class.getDeclaredField("SCREENS");
			field.setAccessible(true);
			java.util.Map<Object, Object> map = (java.util.Map<Object, Object>) field.get(null);
			Class<?> constructorClass = Class.forName("net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor");
			Object constructor = java.lang.reflect.Proxy.newProxyInstance(
				constructorClass.getClassLoader(), new Class<?>[]{constructorClass},
				(proxy, method, args) -> {
					if (method.getName().equals("create")) {
						return new CameraLensScreen(
							(CameraLensMenuHandler) args[0],
							(net.minecraft.world.entity.player.Inventory) args[1],
							(net.minecraft.network.chat.Component) args[2]);
					}
					return null;
				});
			map.put(ModMenuTypes.CAMERA_LENS_MENU, constructor);
			BirdWatchMod.LOGGER.info("[BirdWatch] 相机镜头槽屏幕已注册,SCREENS 大小={}", map.size());
		} catch (Exception e) {
			BirdWatchMod.LOGGER.error("[BirdWatch] 相机镜头槽屏幕注册失败", e);
		}
	}
}
