package com.birdwatch.item;

import net.minecraft.world.item.Item;

/**
 * 生物图鉴(M4b):独立物品,与观鸟手册分开。
 * 右键打开原版生物图鉴界面(拍照解锁生物条目);开局自带(HandbookHandler 发放)。
 */
public class BestiaryItem extends Item {
	public BestiaryItem(Properties properties) {
		super(properties);
	}
}
