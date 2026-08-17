package net.hawthorn.dndsheets.init;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * <p>El único ítem que registra el mod: la <b>ficha</b>, que es el cuerpo de todo lo que reparte —varas,
 * tótems de clase, báculos, cartas de invocación—. Cada uno se distingue por su
 * {@code CustomModelData} (ver {@link net.hawthorn.dndsheets.ItemLook}), no por ser un ítem distinto.</p>
 *
 * <p>Uno y no veinte a propósito: veinte ítems registrados saldrían en {@code /give} y en la búsqueda del
 * inventario creativo, y un {@code /give dndsheets:totem_de_furia} daría una ficha <b>sin</b> la etiqueta
 * NBT que la hace funcionar. Un ítem que solo se obtiene por los comandos del mod no puede engañar a
 * nadie así.</p>
 *
 * <p>{@code stacksTo(1)}: son botones, no material. Apilarlos no significa nada y esconde que cada uno
 * lleva su propia etiqueta.</p>
 */
public class DndsheetsModItems {
	public static final DeferredRegister<Item> REGISTRY = DeferredRegister.create(ForgeRegistries.ITEMS, DndsheetsMod.MODID);

	public static final RegistryObject<Item> TOKEN = REGISTRY.register("token",
		() -> new Item(new Item.Properties().stacksTo(1)));
}
