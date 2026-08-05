package com.birdwatch.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/**
 * 相机物品。右键进入取景器(客户端处理),潜行+右键打开镜头槽;
 * 镜头槽以 CUSTOM_DATA 组件存储镜头 ID,由客户端选择屏幕 + C2S 包写入。
 *
 * 注意:潜行+右键不在此处判定 —— 服务端 isShiftKeyDown 依赖移动包同步,
 * 时机不可靠;改为客户端在 CameraSession 检测潜行后发 C2S 包(CameraLensMenuHandler.open)。
 */
public class CameraItem extends Item {
	private static final String LENS_KEY = "lens";

	public CameraItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		// 镜头槽打开由客户端经 C2S 触发(见 ModNetworking / CameraSession),此处仅占位返回成功
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
		Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("item.birdwatch.camera.tooltip.use"));
		tooltip.accept(Component.translatable("item.birdwatch.camera.tooltip.lens"));
		// 当前所装镜头(类似附魔样式)
		String lensId = getLensId(stack);
		if (lensId.isEmpty()) {
			tooltip.accept(Component.translatable("item.birdwatch.camera.tooltip.no_lens")
				.withStyle(net.minecraft.ChatFormatting.GRAY));
		} else {
			com.birdwatch.camera.LensDefinition def = com.birdwatch.camera.LensRegistry.byId(lensId);
			if (def != null) {
				tooltip.accept(Component.translatable("item.birdwatch.camera.tooltip.lens_equipped",
					Component.translatable(def.nameKey())).withStyle(net.minecraft.ChatFormatting.GREEN));
			}
		}
	}

	public static String getLensId(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return "";
		return data.copyTag().getString(LENS_KEY).orElse("");
	}

	public static void setLensId(ItemStack stack, String lensId) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(LENS_KEY, lensId));
	}
}
