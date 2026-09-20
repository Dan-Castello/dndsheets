package net.hawthorn.dndsheets;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.hawthorn.dndsheets.init.DndsheetsModItems;
import net.minecraft.world.item.ItemStack;

//Repeated pattern across the class-ability "button" items (Rage, Second Wind, Divine Smite,
//Shield, Counterspell, Hunter's Mark, Wild Shape, Bardic Inspiration, Twinned Spell, turn items,
//Rest Kit...): a boolean flag under the "dndsheets" NBT tag that activates the ability when the
//item is used, plus its name and lore.
public class AbilityItem {
	public static ItemStack build(ItemLook look, String flag, Component name, Component... loreLines) {
		return build(look.applyTo(new ItemStack(DndsheetsModItems.TOKEN.get())), flag, name, loreLines);
	}

	/**
	 * <p>Built on a vanilla item, for cases where it <b>has to stay</b> that item. Today only the DM's
	 * Journal: it's a real Book and Quill because {@code /dndjournal publish} reads the pages the DM wrote
	 * in it, and you can't write on one of the mod's sheets. Giving it a custom icon would have made it
	 * prettier and broken.</p>
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
