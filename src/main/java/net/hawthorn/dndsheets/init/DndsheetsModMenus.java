package net.hawthorn.dndsheets.init;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * <p>No menu types any more: the character sheet and the formula editor are plain client screens. The register stays (empty)
 * because {@code DndsheetsMod} adds it to the mod bus and removing it is a separate, pointless edit.</p>
 */
public class DndsheetsModMenus {
	public static final DeferredRegister<MenuType<?>> REGISTRY = DeferredRegister.create(ForgeRegistries.MENU_TYPES, DndsheetsMod.MODID);
}
