package net.hawthorn.dndsheets.compat;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>The only file in the mod that touches Curios types. Kept apart from {@link CuriosCompat} on
 * purpose: the JVM resolves a class's types when loading it, so any reference to Curios from a class
 * that always loads would blow up with {@code NoClassDefFoundError} on an install without Curios.
 * Execution only reaches here after {@code CuriosCompat.isLoaded()} has said yes, and Java's lazy class
 * loading does the rest.</p>
 *
 * <p>Package-private: nobody outside this package should be able to call it without going through the
 * check. This is the class that turns "soft dependency" from intent into a guarantee.</p>
 */
final class CuriosSlots {

	private CuriosSlots() {}

	static List<ItemStack> equippedStacks(Player player) {
		List<ItemStack> stacks = new ArrayList<>();
		//All Curios slots at once (ring, necklace, cloak, belt, and whatever other mods add) instead of
		//requesting them by name: this way it works with whatever set of slots is installed, without a
		//list of identifiers that goes stale the moment someone adds a new one.
		CuriosApi.getCuriosInventory(player).ifPresent(inventory -> {
			IItemHandlerModifiable equipped = inventory.getEquippedCurios();
			for (int slot = 0; slot < equipped.getSlots(); slot++) {
				ItemStack stack = equipped.getStackInSlot(slot);
				if (!stack.isEmpty()) stacks.add(stack);
			}
		});
		return stacks;
	}
}
