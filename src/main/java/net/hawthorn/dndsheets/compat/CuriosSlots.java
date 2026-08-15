package net.hawthorn.dndsheets.compat;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>El único archivo del mod que toca tipos de Curios. Está aparte de {@link CuriosCompat} a propósito:
 * la JVM resuelve los tipos de una clase al cargarla, así que cualquier referencia a Curios desde una
 * clase que se carga siempre reventaría con {@code NoClassDefFoundError} en una instalación sin Curios.
 * Aquí solo se llega después de que {@code CuriosCompat.isLoaded()} haya dicho que sí, y la carga
 * perezosa de clases de Java hace el resto.</p>
 *
 * <p>Package-private: nadie fuera de este paquete debería poder llamarlo sin pasar por la comprobación.
 * Es la clase que convierte "dependencia blanda" de intención en garantía.</p>
 */
final class CuriosSlots {

	private CuriosSlots() {}

	static List<ItemStack> equippedStacks(Player player) {
		List<ItemStack> stacks = new ArrayList<>();
		//Todas las ranuras de Curios de una vez (anillo, collar, capa, cinturón, y las que añadan otros
		//mods) en vez de pedirlas por nombre: así funciona con cualquier conjunto de ranuras instalado, sin
		//una lista de identificadores que se quede vieja en cuanto alguien añada una.
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
