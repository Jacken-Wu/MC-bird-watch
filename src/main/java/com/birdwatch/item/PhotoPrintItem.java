package com.birdwatch.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * 印刷照片物品(M2a):从相册印刷(可裁剪)得到,用于贴入图鉴解锁鸟种。
 *
 * NBT(CUSTOM_DATA 组件,与镜头槽同模式):
 * - photo:相对 photos 目录的照片路径(目录名/文件名.png)
 * - species:照片主体鸟 id
 * - score / tier:该照片中主体鸟的评分与档位(评分 ≥60 才能解锁图鉴)
 * - crop:印刷裁剪矩形(归一化 x,y,w,h,"x,y,w,h" 字符串)
 */
public class PhotoPrintItem extends Item {
	public static final String KEY_PHOTO = "photo";
	public static final String KEY_SPECIES = "species";
	public static final String KEY_SCORE = "score";
	public static final String KEY_TIER = "tier";
	public static final String KEY_CROP = "crop";

	public PhotoPrintItem(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
		Consumer<Component> tooltip, TooltipFlag flag) {
		var data = stack.get(DataComponents.CUSTOM_DATA);
		if (data != null) {
			var tag = data.copyTag();
			String species = tag.getString(KEY_SPECIES).orElse("");
			int score = tag.getInt(KEY_SCORE).orElse(0);
			String tier = tag.getString(KEY_TIER).orElse("");
			if (!species.isEmpty()) {
				tooltip.accept(Component.translatable("item.birdwatch.photo_print.tooltip.species",
					translatedSpecies(species)));
			}
			tooltip.accept(Component.translatable("item.birdwatch.photo_print.tooltip.score", score));
			if (!tier.isEmpty()) {
				tooltip.accept(Component.translatable("item.birdwatch.photo_print.tooltip.tier." + tier));
			}
		}
	}

	/** 物种 id → 显示名(图鉴 lang key,渲染时自动翻译) */
	private static Component translatedSpecies(String speciesId) {
		return Component.translatable("handbook.birdwatch." + speciesId + ".name");
	}
}
