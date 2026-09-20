package net.hawthorn.dndsheets;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * <p>A content name (a pack's {@code "name"}: weapon, spell, monster, magic item, feat, trait, preset,
 * encounter) turned into displayable text. <b>Everything</b> that puts one of those names on screen —
 * chat, item tooltip, GUI list, compendium — has to go through here.</p>
 *
 * <p>The reason is that language is chosen by each client, not the server: with
 * {@code Component.literal(name)} the name resolves where it's built — the server — and everyone reads
 * it the same, so a player with the game in English would receive a "Daga" and a "Báculo de Rayo de
 * Fuego". Since the name also travels inside the ItemStack's NBT, it stayed in Spanish forever.</p>
 *
 * <p>The mod's stock packs carry a language <b>key</b> in that field
 * ({@code "name": "content.dndsheets.weapon.dagger"}), which each client resolves in its own language. A
 * pack hand-written by a DM carries the literal name ({@code "name": "King's Sword"}) and nothing
 * special needs to be done: Minecraft already returns the key as-is when it doesn't exist in the
 * language files, so that case renders exactly as before. That's why there's no need to distinguish
 * them here or mark the pack in any way.</p>
 */
public final class ContentNames {

	private ContentNames() {
	}

	public static MutableComponent of(String name) {
		if (name == null || name.isEmpty()) return Component.empty();
		//A % in a hand-written name ("Potion 50%") would be mistaken by TranslatableContents for a
		//format placeholder and would eat the text from there on. No key in the mod carries a %, so
		//ruling it out here loses nothing and does save a DM's pack.
		return name.indexOf('%') >= 0 ? Component.literal(name) : Component.translatable(name);
	}

	/**
	 * <p>For places that need a {@code String} and not a {@code Component}: a roll-log line summary, or
	 * the name saved on the sheet for the effects panel.</p>
	 *
	 * <p>ponytail: this resolves in the SERVER's language, not the viewer's — that's the only option
	 * possible when the destination is plain text. Fine for a summary; if some day the roll log also
	 * needs to follow the client's language, {@code Component} has to be carried all the way through
	 * that pipeline instead of a {@code String}.</p>
	 */
	public static String plain(String name) {
		return name == null ? "" : of(name).getString();
	}
}
