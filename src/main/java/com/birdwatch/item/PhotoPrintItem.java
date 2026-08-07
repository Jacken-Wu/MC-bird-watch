package com.birdwatch.item;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.print.PrintStore;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * 印刷照片物品(M2a):从相册印刷(可裁剪)得到,用于贴入图鉴解锁鸟种。
 *
 * NBT(CUSTOM_DATA 组件,与镜头槽同模式):
 * - photo:印刷图 printId(服务端存档 prints/&lt;printId&gt;.png,与物品绑定)
 * - species:照片主体鸟 id
 * - score / tier:该照片中主体鸟的评分与档位(评分 ≥60 才能解锁图鉴)
 * - crop:印刷裁剪矩形(归一化 x,y,w,h,"x,y,w,h" 字符串)
 *
 * 生命周期:物品销毁(烧毁/爆炸 onDestroyed)、贴入图鉴消耗 → 服务端删除对应印刷图文件;
 * 自然消失由 PrintStore 周期 GC 兜底。
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

	/**
	 * 物品实体被销毁(烧毁/爆炸/仙人掌等)→ 删除绑定的印刷图文件。
	 * 注意:掉落物自然消失(5 分钟,age≥6000)不触发本回调,由 PrintStore 周期 GC 兜底。
	 */
	@Override
	public void onDestroyed(ItemEntity itemEntity) {
		var data = itemEntity.getItem().get(DataComponents.CUSTOM_DATA);
		if (data == null || !(itemEntity.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		String printId = data.copyTag().getString(KEY_PHOTO).orElse("");
		if (printId.isBlank()) {
			return;
		}
		if (PrintStore.delete(serverLevel.getServer(), printId)) {
			BirdWatchMod.LOGGER.info("[Print] 物品销毁,已删除印刷图 {}", printId);
		}
	}
}
