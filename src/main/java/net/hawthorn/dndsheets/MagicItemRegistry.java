package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <p>Magic items. Sixth content type, with the same shape as the other five:
 * {@link NamedRegistry} in memory + {@link JsonRegistryLoader} from a JSON per folder.</p>
 *
 * <p><b>Why importing it doesn't look like spells and monsters:</b> the SRD publishes magic items only
 * as <em>prose</em> — name, rarity, category, and a description paragraph. There's no bonus field or
 * resistance field to read, so their mechanics can't be derived automatically without making them up.
 * Hence an item has two halves: the <b>reference</b> half (name, rarity, description), importable
 * wholesale and already useful for a DM to look up and hand out, and the <b>mechanical</b> half (AC,
 * saves, resistances), which is written by hand and only exists where it genuinely repeats a pattern.</p>
 *
 * <p>An item with no mechanics isn't a broken item: it's an item the DM narrates, which is how most
 * magic items work at a real table.</p>
 */
public class MagicItemRegistry {

	/**
	 * @param acBonus        adds to AC while equipped or attuned.
	 * @param saveBonus      adds to all saving throws (Ring/Cloak of Protection).
	 * @param affinities     resistances it grants, same vocabulary as the rest of the mod.
	 * @param grantsSpellId  spell it lets you cast (wands and staves), or {@code null}.
	 * @param attunement     whether it needs attunement. In 5e it caps a character at 3 items, which is
	 *                       what stops someone from stacking twenty rings — and on top of that, in
	 *                       Minecraft it solves the fact that there's no ring or cloak slot to "wear" them in.
	 */
	public record MagicItem(String id, String name, String rarity, String description, String itemId,
	                        int acBonus, int saveBonus, Map<String, String> affinities,
	                        String grantsSpellId, boolean attunement,
	                        String healDice, String temporaryHpDice, String grantsCondition,
	                        Map<String, String> temporaryAffinities, int durationRounds) {

		/**
		 * <p>Used by consuming it (potions, oils). Its effects are NOT passive: modeling a potion of
		 * resistance as a permanent affinity would protect whoever carries the bottle in their pocket
		 * without drinking it, which is exactly the false positive that had to be excluded when deriving
		 * mechanics from prose.</p>
		 */
		public boolean isConsumable() {
			return healDice != null || temporaryHpDice != null || grantsCondition != null
				|| !temporaryAffinities.isEmpty();
		}

		/** A purely narrative item: the DM describes it, the engine has nothing to apply. */
		public boolean hasMechanics() {
			return acBonus != 0 || saveBonus != 0 || !affinities.isEmpty() || grantsSpellId != null
				|| isConsumable();
		}
	}

	private static final NamedRegistry<MagicItem> REGISTRY = new NamedRegistry<>("magic item", MagicItem::id);

	public static void register(MagicItem item) { REGISTRY.register(item); }

	@Nullable
	public static MagicItem get(String id) { return REGISTRY.get(id); }

	public static Set<String> ids() { return REGISTRY.ids(); }

	public static boolean remove(String id) { return REGISTRY.remove(id); }

	private static final JsonRegistryLoader<MagicItem> LOADER =
		new JsonRegistryLoader<>("magic item", MagicItemRegistry::parse, MagicItemRegistry::register);

	/** Loads from an already-parsed JSON (datapack or another mod's jar) — see ContentDatapackLoader. */
	public static int loadJson(com.google.gson.JsonElement root, String source, java.util.function.Consumer<String> onId) {
		return LOADER.loadJson(root, source, onId);
	}

	public static int loadFile(Path file) throws IOException { return LOADER.loadFile(file); }

	public static MagicItem parse(JsonObject json) {
		String id = json.get("id").getAsString();
		Map<String, String> affinities = readAffinities(json, "damageAffinities");
		Map<String, String> temporaryAffinities = readAffinities(json, "temporaryAffinities");
		return new MagicItem(
			id,
			json.has("name") ? json.get("name").getAsString() : id,
			json.has("rarity") ? json.get("rarity").getAsString() : "common",
			json.has("description") ? json.get("description").getAsString() : "",
			//The vanilla item that lends it its appearance. A gold ingot by default: neutral, and not
			//mistaken for a real weapon or armor.
			json.has("item") ? json.get("item").getAsString() : "minecraft:gold_ingot",
			json.has("acBonus") ? json.get("acBonus").getAsInt() : 0,
			json.has("saveBonus") ? json.get("saveBonus").getAsInt() : 0,
			affinities,
			json.has("grantsSpell") ? json.get("grantsSpell").getAsString() : null,
			json.has("attunement") && json.get("attunement").getAsBoolean(),
			json.has("healDice") ? json.get("healDice").getAsString() : null,
			json.has("temporaryHpDice") ? json.get("temporaryHpDice").getAsString() : null,
			json.has("grantsCondition") ? json.get("grantsCondition").getAsString() : null,
			temporaryAffinities,
			//10 rounds = 1 minute in 5e, which is how long most potions last.
			json.has("durationRounds") ? json.get("durationRounds").getAsInt() : 10);
	}

	private static Map<String, String> readAffinities(JsonObject json, String field) {
		Map<String, String> result = new HashMap<>();
		if (!json.has(field)) return result;
		JsonObject declared = json.getAsJsonObject(field);
		for (String type : declared.keySet()) {
			result.put(type.toLowerCase(Locale.ROOT), declared.get(type).getAsString().toLowerCase(Locale.ROOT));
		}
		return result;
	}

	//--- ItemStack NBT tag, same pattern as quickSpell and monsterSpawn ---

	public static ItemStack tag(ItemStack stack, String itemId) {
		CompoundTag tag = stack.getOrCreateTag();
		CompoundTag dnd = tag.getCompound("dndsheets");
		dnd.putString("magicItem", itemId);

		//A wand or staff that casts a spell is ALSO tagged as a quick staff: that path already exists in
		//full (see QuickSpellManager and AbilityItemDispatcher) and does exactly what's needed — right-click
		//casts the spell using the holder's ability scores and spell slots. Reusing it is free; writing a
		//second "item that casts something" mechanism isn't.
		MagicItem item = get(itemId);
		if (item != null && item.grantsSpellId() != null && SpellRegistry.get(item.grantsSpellId()) != null) {
			dnd.putString("quickSpell", item.grantsSpellId());
		}

		tag.put("dndsheets", dnd);
		return stack;
	}

	@Nullable
	public static String magicItemIdOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets")) return null;
		CompoundTag dnd = tag.getCompound("dndsheets");
		String id = dnd.contains("magicItem") ? dnd.getString("magicItem") : null;
		return id == null || id.isEmpty() ? null : id;
	}

	//--- Which items are currently affecting a player ---

	//It's 3 in 5e, and it's not a round number by coincidence: it's the brake that stops items from
	//stacking without limit. Here it also solves a Minecraft-specific problem — there's no ring or cloak
	//slot, so without attunement there would be no way to "wear" a Ring of Protection.
	public static final int MAX_ATTUNED = 3;

	public static List<String> attunedIds(JsonObject sheet) {
		List<String> attuned = new ArrayList<>();
		if (sheet == null || !sheet.has("attunedItems")) return attuned;
		for (JsonElement el : sheet.getAsJsonArray("attunedItems")) attuned.add(el.getAsString());
		return attuned;
	}

	/** @return false if the limit was already reached or if it was already attuned. */
	public static boolean attune(JsonObject sheet, String itemId) {
		List<String> attuned = attunedIds(sheet);
		if (attuned.contains(itemId) || attuned.size() >= MAX_ATTUNED) return false;
		attuned.add(itemId);
		writeAttuned(sheet, attuned);
		return true;
	}

	public static boolean unattune(JsonObject sheet, String itemId) {
		List<String> attuned = attunedIds(sheet);
		if (!attuned.remove(itemId)) return false;
		writeAttuned(sheet, attuned);
		return true;
	}

	private static void writeAttuned(JsonObject sheet, List<String> attuned) {
		JsonArray array = new JsonArray();
		for (String id : attuned) array.add(id);
		sheet.add("attunedItems", array);
	}

	/**
	 * <p>The items whose effects are active on that player. One that requires attunement counts only if
	 * it's attuned; one that doesn't, only if it's held in hand or worn as armor.</p>
	 *
	 * <p>That distinction is what avoids the absurd case of a player with an inventory full of items
	 * stacking bonuses for the mere fact of carrying them.</p>
	 */
	public static List<MagicItem> activeFor(Player player, JsonObject sheet) {
		List<MagicItem> active = new ArrayList<>();
		List<String> attuned = attunedIds(sheet);

		List<String> wornIds = new ArrayList<>();
		for (ItemStack stack : equippedStacks(player)) {
			String id = magicItemIdOf(stack);
			if (id != null) wornIds.add(id);
		}

		for (String id : attuned) {
			MagicItem item = get(id);
			if (item == null) continue;
			//With Curios installed there's somewhere to wear a ring or a cloak, so the same rule as 5e
			//applies: attuned AND equipped. Without Curios that slot doesn't exist, and requiring it would
			//leave the item permanently unusable — there, attunement does double duty.
			if (net.hawthorn.dndsheets.compat.CuriosCompat.isLoaded() && !wornIds.contains(id)) continue;
			active.add(item);
		}

		for (String id : wornIds) {
			if (attuned.contains(id)) continue; //Already counted above: don't apply it twice.
			MagicItem item = get(id);
			if (item != null && !item.attunement()) active.add(item);
		}
		return active;
	}

	/**
	 * <p>What the player is actually wearing. With Curios installed this includes its slots (ring,
	 * necklace, cloak, belt), which is where a Ring of Protection should naturally go; without Curios,
	 * the list is the usual one and those items still just depend on attunement.</p>
	 *
	 * <p>The integration is soft: {@link net.hawthorn.dndsheets.compat.CuriosCompat} returns an empty
	 * list if Curios isn't present, so there's no branch here to maintain.</p>
	 */
	private static List<ItemStack> equippedStacks(Player player) {
		List<ItemStack> stacks = new ArrayList<>();
		stacks.add(player.getMainHandItem());
		stacks.add(player.getOffhandItem());
		player.getArmorSlots().forEach(stacks::add);
		stacks.addAll(net.hawthorn.dndsheets.compat.CuriosCompat.equippedStacks(player));
		return stacks;
	}
}
