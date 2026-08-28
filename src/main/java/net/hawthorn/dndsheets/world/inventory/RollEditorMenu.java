package net.hawthorn.dndsheets.world.inventory;

import net.hawthorn.dndsheets.init.DndsheetsModMenus;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;

/** Menú sin ranuras del editor de tiradas — mismo papel y misma limpieza que {@link CharacterSheetMenu}. */
public class RollEditorMenu extends AbstractContainerMenu {
	public final static HashMap<String, Object> guistate = new HashMap<>();

	//El FriendlyByteBuf lo exige IForgeMenuType (ver DndsheetsModMenus): llega vacío y no se lee.
	public RollEditorMenu(int id, Inventory inv, FriendlyByteBuf extraData) {
		super(DndsheetsModMenus.ROLL_EDITOR.get(), id);
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	@Override
	public ItemStack quickMoveStack(Player playerIn, int index) {
		return ItemStack.EMPTY;
	}
}
