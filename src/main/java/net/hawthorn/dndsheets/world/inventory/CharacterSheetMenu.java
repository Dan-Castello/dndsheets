package net.hawthorn.dndsheets.world.inventory;

import net.hawthorn.dndsheets.init.DndsheetsModMenus;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;

/**
 * <p>Slotless menu. It exists for two reasons, neither related to inventories: so the sheet can be
 * an {@code AbstractContainerScreen} (and thereby survive the E key, mouse capture, and the
 * lifecycle Minecraft already knows how to handle), and to hold the {@code guistate} that
 * {@code CharacterSheetScreen} shares with its load and save procedures.</p>
 *
 * <p>What it inherited from MCreator and no longer has: {@code bound}/{@code boundItemMatcher}/
 * {@code boundEntity}/{@code boundBlockEntity} (never assigned, so {@code stillValid} always took the
 * same branch and returned {@code true}), an {@code ItemStackHandler(0)} nobody read, an empty slot
 * map exposed through a {@code Supplier} nobody called, and the position of the block that opened
 * the menu — which traveled from the message, through a {@code FriendlyByteBuf}, into {@code x/y/z}
 * fields on the screen that no method ever read.</p>
 */
public class CharacterSheetMenu extends AbstractContainerMenu {
	public final static HashMap<String, Object> guistate = new HashMap<>();
	public final Player entity;

	//The FriendlyByteBuf is required by the IForgeMenuType factory (see DndsheetsModMenus), not by this
	//menu: it arrives empty and is NOT read. If data ever needs to be sent to the screen on opening,
	//this is the place — but then it also has to be written on whoever calls NetworkHooks.openScreen.
	public CharacterSheetMenu(int id, Inventory inv, FriendlyByteBuf extraData) {
		super(DndsheetsModMenus.CHARACTER_SHEET.get(), id);
		this.entity = inv.player;
	}

	//No block or entity to watch: the sheet closes with Esc or its own keybind, not by walking away
	//from anything.
	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	//No slots, so shift-click has nowhere to move anything.
	@Override
	public ItemStack quickMoveStack(Player playerIn, int index) {
		return ItemStack.EMPTY;
	}
}
