package net.hawthorn.dndsheets;

import net.minecraft.world.item.ItemStack;

/**
 * <p>The look of each of the mod's items: its own texture, instead of a renamed vanilla item.</p>
 *
 * <p><b>Why it was needed.</b> Everything this mod hands out —the DM Wand, class totems, staves,
 * summon cards— was a Minecraft item under another name: a stick, a red dye, a rabbit's foot. It
 * worked, but in the hotbar of a player with a dozen things on it, you can't tell Divine Smite from
 * the glowstone dust they already had, and "everything is the same thing with a different name" is
 * exactly the problem that was just fixed in the bestiary.</p>
 *
 * <p><b>How.</b> A single registered item ({@code dndsheets:token}) plus {@code CustomModelData}, which
 * is the mechanism Minecraft provides for this. Advantages over the two alternatives: <b>no</b> vanilla
 * model gets touched —overriding {@code minecraft:item/compass} to put an icon here would change the
 * compass for everyone, and the vanilla one has 32 angle variants—, and no twenty items get registered
 * that would show up in {@code /give} handing out items without their NBT tag, i.e. dead ones.</p>
 *
 * <p><b>The number travels by position, so this is APPEND-ONLY.</b> The {@code CustomModelData} is
 * {@code ordinal() + 1} and gets written into every ItemStack that already exists in someone's world.
 * Inserting a constant in the middle changes the icon of everything handed out so far. New constants
 * go at the end. Same deal as the network messages (invariant 1).</p>
 *
 * <p>Each one's texture is {@code assets/dndsheets/textures/item/<lowercase name>.png}, drawn by
 * {@code tools/generate_item_icons.py} — original art, because the mod can't redistribute anyone else's.</p>
 */
public enum ItemLook {
	DM_WAND,
	MOVE_WAND,
	REST_KIT,
	TURN_NEXT,
	TURN_UNDO,
	TURN_ACTIONS,
	RAGE,
	SECOND_WIND,
	INSPIRATION,
	WILD_SHAPE,
	TWINNED,
	SMITE,
	HUNTERS_MARK,
	SHIELD,
	COUNTERSPELL,
	TURN_UNDEAD,
	HELP,
	STAFF,
	SUMMON_CARD,
	SHOVE,
	ROLEPLAY;

	/** The value the {@code token.json} model looks up in its overrides list. */
	public int customModelData() {
		return ordinal() + 1;
	}

	/** {@code assets/dndsheets/textures/item/<this>.png} and {@code .../models/item/<this>.json}. */
	public String textureName() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	/** Paints this look onto an already-built stack. */
	public ItemStack applyTo(ItemStack stack) {
		stack.getOrCreateTag().putInt("CustomModelData", customModelData());
		return stack;
	}
}
