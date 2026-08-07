package net.hawthorn.dndsheets;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

//Patrón repetido en los ítems "botón" de habilidad de clase (Furia, Segundo Aliento, Castigo Divino,
//Escudo, Contrahechizo, Marca del Cazador, Forma Salvaje, Inspiración Bárdica, Hechizo Gemelo, ítems de
//turno, Kit de Descanso...): un flag booleano bajo la etiqueta NBT "dndsheets" que activa la habilidad al
//usar el ítem, más su nombre y lore. Ver AUDIT_TECHNICAL.md A-DUP-2.
public class AbilityItem {
	public static boolean hasFlag(ItemStack stack, String flag) {
		CompoundTag tag = stack.getTag();
		return tag != null && tag.contains("dndsheets") && tag.getCompound("dndsheets").getBoolean(flag);
	}

	public static ItemStack build(Item item, String flag, Component name, Component... loreLines) {
		ItemStack stack = new ItemStack(item);
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
