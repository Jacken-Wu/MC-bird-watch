package com.birdwatch.menu;

import com.birdwatch.camera.LensRegistry;
import com.birdwatch.item.CameraItem;
import com.birdwatch.registry.ModItems;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 相机镜头槽容器(原版储物袋逻辑):1 格镜头槽 + 玩家背包。
 * 镜头槽只允许放入镜头;关闭菜单时写回相机 NBT。
 */
public class CameraLensMenuHandler extends AbstractContainerMenu {
	private static final int LENS_SLOT_INDEX = 0;

	private final Container lensInventory = new SimpleContainer(1);

	public CameraLensMenuHandler(int syncId, Inventory playerInventory) {
		super(ModMenuTypes.CAMERA_LENS_MENU, syncId);
		// 镜头槽(只允许镜头)
		addSlot(new Slot(lensInventory, 0, 80, 20) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return LensRegistry.isLensItem(stack.getItem());
			}
		});
		// 玩家主物品栏
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 51 + row * 18));
			}
		}
		// 热键栏
		for (int col = 0; col < 9; col++) {
			addSlot(new Slot(playerInventory, col, 8 + col * 18, 109));
		}
	}

	/**
	 * 打开镜头槽菜单。由 C2S 处理器调用(客户端判定潜行后请求),
	 * 不在 Item.use 中判定服务端潜行标志(移动包同步的潜行状态时机不可靠)。
	 * 必须用 ExtendedMenuProvider 打开 —— CAMERA_LENS_MENU 是 ExtendedMenuType
	 * (26.2 MenuType 构造器 private 故用 Fabric 扩展),普通 MenuProvider 会抛
	 * "must be opened with an ExtendedMenuProvider"。
	 */
	public static void open(Player player) {
		player.openMenu(new ExtendedMenuProvider<>() {
			@Override
			public Object getScreenOpeningData(ServerPlayer serverPlayer) {
				return ModMenuTypes.NO_DATA; // 空数据占位(编码器不允许 null)
			}

			@Override
			public Component getDisplayName() {
				return Component.translatable("screen.birdwatch.lens_mount");
			}

			@Override
			public CameraLensMenuHandler createMenu(int syncId, Inventory inv, Player p) {
				CameraLensMenuHandler menu = new CameraLensMenuHandler(syncId, inv);
				menu.loadFromCamera(player.getMainHandItem());
				return menu;
			}
		});
	}

	/** 打开时从相机读取当前镜头 */
	public void loadFromCamera(ItemStack camera) {
		String lensId = CameraItem.getLensId(camera);
		if (!lensId.isEmpty()) {
			ItemStack lens = new ItemStack(BuiltInRegistries.ITEM.getValue(
				Identifier.fromNamespaceAndPath(com.birdwatch.BirdWatchMod.MOD_ID, lensId)));
			lensInventory.setItem(0, lens);
		}
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		ItemStack result = ItemStack.EMPTY;
		Slot slot = this.slots.get(index);
		if (slot != null && slot.hasItem()) {
			ItemStack stack = slot.getItem();
			result = stack.copy();
			if (index == LENS_SLOT_INDEX) {
				// 镜头 → 背包
				if (!this.moveItemStackTo(stack, 1, this.slots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else {
				// 背包 → 镜头槽(仅镜头可入)
				if (!this.moveItemStackTo(stack, LENS_SLOT_INDEX, 1, false)) {
					return ItemStack.EMPTY;
				}
			}
			if (stack.isEmpty()) {
				slot.setByPlayer(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}
		}
		return result;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		// 写回相机(镜头槽内容 → 相机 NBT)
		ItemStack camera = player.getMainHandItem();
		if (camera.is(ModItems.CAMERA)) {
			ItemStack lens = lensInventory.getItem(0);
			CameraItem.setLensId(camera, lens.isEmpty()
				? "" : BuiltInRegistries.ITEM.getKey(lens.getItem()).getPath());
		}
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}
}
