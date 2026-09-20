package net.hawthorn.dndsheets.init;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * <p>The only item the mod registers: the <b>token</b>, which is the body of everything it hands out —
 * wands, class totems, staves, summon cards. Each one is distinguished by its {@code CustomModelData}
 * (see {@link net.hawthorn.dndsheets.ItemLook}), not by being a separate item.</p>
 *
 * <p>One and not twenty, deliberately: twenty registered items would show up in {@code /give} and in
 * creative-inventory search, and a {@code /give dndsheets:rage_totem} would hand out a token
 * <b>without</b> the NBT tag that makes it work. An item obtainable only through the mod's commands
 * can't mislead anyone that way.</p>
 *
 * <p>{@code stacksTo(1)}: these are buttons, not materials. Stacking them wouldn't mean anything and
 * would hide the fact that each one carries its own tag.</p>
 */
public class DndsheetsModItems {
	public static final DeferredRegister<Item> REGISTRY = DeferredRegister.create(ForgeRegistries.ITEMS, DndsheetsMod.MODID);

	public static final RegistryObject<Item> TOKEN = REGISTRY.register("token",
		() -> new Item(new Item.Properties().stacksTo(1)));
}
