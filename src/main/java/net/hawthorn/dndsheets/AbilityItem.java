package net.hawthorn.dndsheets;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.hawthorn.dndsheets.init.DndsheetsModItems;
import net.minecraft.world.item.ItemStack;

//Patrón repetido en los ítems "botón" de habilidad de clase (Furia, Segundo Aliento, Castigo Divino,
//Escudo, Contrahechizo, Marca del Cazador, Forma Salvaje, Inspiración Bárdica, Hechizo Gemelo, ítems de
//turno, Kit de Descanso...): un flag booleano bajo la etiqueta NBT "dndsheets" que activa la habilidad al
//usar el ítem, más su nombre y lore.
public class AbilityItem {
	public static ItemStack build(ItemLook look, String flag, Component name, Component... loreLines) {
		return build(look.applyTo(new ItemStack(DndsheetsModItems.TOKEN.get())), flag, name, loreLines);
	}

	/**
	 * <p>Sobre un ítem de vanilla, para lo que <b>tiene que seguir siendo</b> ese ítem. Hoy solo el
	 * Cuaderno del DM: es un Libro y Pluma de verdad porque {@code /dndjournal publish} lee las páginas
	 * que el DM escribió en él, y en una ficha del mod no se puede escribir. Darle un icono propio lo
	 * habría dejado más bonito y roto.</p>
	 */
	public static ItemStack build(net.minecraft.world.item.Item item, String flag, Component name, Component... loreLines) {
		return build(new ItemStack(item), flag, name, loreLines);
	}

	private static ItemStack build(ItemStack stack, String flag, Component name, Component... loreLines) {
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean(flag, true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(name);

		if (loreLines.length > 0) {
			ListTag lore = new ListTag();
			for (Component line : loreLines) lore.add(StringTag.valueOf(Component.Serializer.toJson(line)));
			stack.getOrCreateTagElement("display").put("Lore", lore);
		}
		return stack;
	}
}
