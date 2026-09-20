package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;

/**
 * <p>The inventory belongs to the <b>character</b>, not the body carrying it. Switching characters saves
 * the outgoing one's and equips the incoming one's: the wizard's staff doesn't travel to the fighter.</p>
 *
 * <p>It's the last of the "player value standing in for character value" family (level, health, per-rest
 * resources), and the scariest one to touch, because a mistake here <b>deletes items</b> and there's no
 * undo. Hence the strict operation order and the conservative approach below.</p>
 *
 * <p><b>Saved as SNBT inside the sheet</b> instead of a separate file: the sheet is already saved whole
 * and alone on every change, and a second per-character file would be one more thing that can drift out
 * of sync with the first. The {@code ListTag} is wrapped in a compound because Minecraft's parser reads
 * compounds, not bare lists.</p>
 *
 * <p><b>A character with no saved inventory starts empty.</b> That's not a loss: what you were carrying
 * was just written to the sheet of the character you're taking off, and comes back whole the next time
 * you put it on. The alternative —keeping what you're wearing— would duplicate every item on every
 * switch.</p>
 */
final class CharacterInventory {

	private static final String FIELD = "inventory";
	private static final String ITEMS = "items";

	private CharacterInventory() {
	}

	/**
	 * <p>Swaps the body's inventory: saves the outgoing character's and equips the incoming one's.</p>
	 *
	 * <p>Order matters and isn't negotiable: what's there is <b>saved and persisted first</b>, and only
	 * afterward is it cleared. If something fails midway, the worst that can happen is the player ends up
	 * with the previous character's inventory — annoying and reversible. Clearing first, the worst case
	 * would be having nothing anywhere.</p>
	 */
	static void swap(ServerPlayer player, String outgoingId, JsonObject outgoing, JsonObject incoming) {
		if (outgoing == incoming) return; //Putting on the one you're already wearing: nothing to move.

		if (outgoing != null) {
			outgoing.addProperty(FIELD, serialize(player));
			//To disk NOW, before clearing anything. This is the line that turns a failure into a nuisance.
			SheetLoader.saveCharacterSheet(outgoingId, outgoing);
		}

		player.getInventory().clearContent();
		if (incoming != null && incoming.has(FIELD)) {
			restore(player, incoming.get(FIELD).getAsString());
			//Reported while playing: the inventory didn't look changed until opened by hand. Changing
			//slots server-side does NOT repaint the hotbar by itself — the player's menu sends the client
			//what's changed since its last snapshot, and a full swap done outside a menu interaction goes
			//unannounced. broadcastFullState forces the entire send, which is what's needed when what
			//changed is "everything".
			player.inventoryMenu.broadcastFullState();
		} else if (outgoing != null) {
			//It's said out loud. Ending up empty-handed with no explanation reads as "the mod deleted my
			//stuff", when what happened is the exact opposite: it's saved with the other character.
			player.sendSystemMessage(net.minecraft.network.chat.Component
				.translatable("chat.dndsheets.character.inventory_swapped").withStyle(ChatFormatting.GRAY));
			player.inventoryMenu.broadcastFullState(); //Clearing it also needs to be announced, for the same reason.
		}
	}

	private static String serialize(ServerPlayer player) {
		CompoundTag root = new CompoundTag();
		root.put(ITEMS, player.getInventory().save(new ListTag()));
		return root.toString();
	}

	private static void restore(ServerPlayer player, String snbt) {
		try {
			CompoundTag root = TagParser.parseTag(snbt);
			//load() does NOT clear what was there: it writes over the slots it carries. That's why
			//clearing happens earlier, in swap, and not here — otherwise slots the new character doesn't
			//use would keep the old character's items.
			player.getInventory().load(root.getList(ITEMS, 10));
		} catch (Exception e) {
			//A sheet with a corrupted field leaves the player empty-handed, not unable to play. It's
			//logged because that's the only thing that would allow recovering it by hand from the file.
			DndsheetsMod.LOGGER.error("dndsheets: could not restore the character inventory of {}: {}",
				player.getName().getString(), e.getMessage());
		}
	}
}
