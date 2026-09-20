package net.hawthorn.dndsheets.world.inventory;

import net.hawthorn.dndsheets.init.DndsheetsModMenus;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;

/** Slotless menu for the advanced editor — same role and same cleanup as {@link CharacterSheetMenu}. */
public class AdvancedRollEditorMenu extends AbstractContainerMenu {
	public final static HashMap<String, Object> guistate = new HashMap<>();

	//The FriendlyByteBuf is required by IForgeMenuType (see DndsheetsModMenus): it arrives empty and isn't read.
	public AdvancedRollEditorMenu(int id, Inventory inv, FriendlyByteBuf extraData) {
		super(DndsheetsModMenus.ADVANCED_ROLL_EDITOR.get(), id);
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
