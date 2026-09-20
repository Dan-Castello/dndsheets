
package net.hawthorn.dndsheets.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import javax.annotation.Nullable;
import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class SheetServerMessage {
	//The only fields the player's own sheet (CharacterSheetSaveProcedure/RollIndex) ever writes.
	//Everything else (gold, spell slots, damage affinities, advantage, character level, warlock pact,
	//class resources, death saves...) is written by the SERVER on its own, through
	//commands/messages already gated behind operator permission (SheetAdjustMessage, /dndsheet...) —
	//without this list, a modified client could send back the full JSON the server had given it, with
	//those fields altered, and completely bypass the lock those other messages enforce.
	private static final Set<String> PLAYER_EDITABLE_KEYS = Set.of(
		"characterName", "characterClass", "characterRace", "background",
		"hitPoints", "hitPointsMax", "hitPointsTemp", "armorClass", "level", "speed",
		"hitDiceTypes", "hitDice", "proficiencyBonus",
		"strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"
	);

	//The FORMULA of a roll (not just its result) is real power: "1d20 + 999" always hits. Previously
	//any player could rewrite it from their own sheet (checks/saves/skills/attacks traveled in
	//PLAYER_EDITABLE_KEYS with no further control) — now only an operator can touch them. "attacks" is
	//a partial exception: CharacterSheetLoadProcedure.autoPopulateWeapons DOES need to keep being able
	//to add, from the regular player's side, a new entry for each recognized weapon they're carrying
	//(with the config's default expression, never a made-up one) — see mergeAttacks.
	private static final Set<String> OP_ONLY_ROLL_KEYS = Set.of("checks", "saves", "skills", "attacks");

	//[min,max] range for each numeric field in PLAYER_EDITABLE_KEYS. Without this, a modified client
	//could send e.g. "dexterity":"999999" and become practically invulnerable/unstoppable: these
	//values feed directly into real AC (CombatManager.armorClassOf), real max HP
	//(SheetLoader.applyClassHitPoints), and the $str/$dex/.../$prof modifiers of ANY roll
	//(DiceManager.roll). Fields not listed here (name, class, race, background, hit die type) are free
	//text with no numeric range to enforce.
	private static final Map<String, int[]> NUMERIC_FIELD_BOUNDS = Map.ofEntries(
		Map.entry("strength", new int[]{1, 30}),
		Map.entry("dexterity", new int[]{1, 30}),
		Map.entry("constitution", new int[]{1, 30}),
		Map.entry("intelligence", new int[]{1, 30}),
		Map.entry("wisdom", new int[]{1, 30}),
		Map.entry("charisma", new int[]{1, 30}),
		Map.entry("proficiencyBonus", new int[]{0, 20}),
		Map.entry("hitDice", new int[]{0, 100}),
		Map.entry("level", new int[]{0, 20}),
		Map.entry("hitPoints", new int[]{0, 1_000_000}),
		Map.entry("hitPointsMax", new int[]{0, 1_000_000}),
		Map.entry("hitPointsTemp", new int[]{0, 1_000_000}),
		Map.entry("armorClass", new int[]{0, 100})
	);

	byte[] data;

	public SheetServerMessage(byte[] data) {
		this.data = data;
	}

	public SheetServerMessage(FriendlyByteBuf buffer) {
		this.data = buffer.readByteArray();
	}

	public static void buffer(SheetServerMessage message, FriendlyByteBuf buffer) {
		buffer.writeByteArray(message.data);
	}

	public static void handler(SheetServerMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> handle(context.getSender(), message.data));
	}

	public static void handle(Player entity, byte[] data) {
		String uuid = entity.getStringUUID();
		JsonObject incoming;
		try {
			incoming = JsonParser.parseString(new String(data, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (JsonSyntaxException | IllegalStateException e) {
			//Payload from a client (any client, not just an op) that isn't valid JSON or isn't an object:
			//the message is discarded instead of crashing the server's main thread with an uncaught exception.
			DndsheetsMod.LOGGER.warn("Discarded SheetServerMessage with invalid JSON from {}: {}", uuid, e.toString());
			return;
		}

		JsonObject sheet = SheetLoader.getServerSheet(uuid);
		if (sheet == null) return; //Shouldn't happen: SheetLoader.clientJoinedServer already gives a sheet to every connected player.

		for (String key : PLAYER_EDITABLE_KEYS) {
			if (!incoming.has(key)) continue;
			JsonElement sanitized = sanitizeIncoming(key, incoming.get(key));
			if (sanitized != null) sheet.add(key, sanitized);
		}

		boolean isOp = entity.hasPermissions(2);
		for (String key : OP_ONLY_ROLL_KEYS) {
			if (!incoming.has(key)) continue;
			if (isOp) {
				sheet.add(key, incoming.get(key));
			} else if ("attacks".equals(key)) {
				sheet.add(key, mergeAttacks(sheet.has(key) ? sheet.get(key) : null, incoming.get(key)));
			}
			//checks/saves/skills: a regular player has no legitimate reason to touch these (unlike
			//"attacks", nothing auto-populates them), so their attempt is silently ignored — the
			//server's sheet keeps whatever it already had.
		}

		SheetLoader.saveServer(sheet, uuid);
		SheetLoader.applyClassHitPoints(entity, sheet);
	}

	//Single entry point for PLAYER_EDITABLE_KEYS data: discards non-primitive values (an
	//object/array where text or a number was expected would later crash the server thread the first
	//time CombatManager.abilityModifier/SheetLoader.sheetInt tried to read it as a number) and clamps
	//numeric fields to NUMERIC_FIELD_BOUNDS. If the value isn't valid for the field, it's discarded
	//entirely and the server's sheet keeps what it already had, instead of storing garbage.
	@Nullable
	private static JsonElement sanitizeIncoming(String key, JsonElement value) {
		if (!value.isJsonPrimitive()) return null;
		int[] bounds = NUMERIC_FIELD_BOUNDS.get(key);
		if (bounds == null) return value;
		try {
			int n = Integer.parseInt(value.getAsString());
			n = Math.max(bounds[0], Math.min(bounds[1], n));
			return new JsonPrimitive(String.valueOf(n));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	//Safe merge of "attacks" for a player WITHOUT operator permission: keeps every entry that ALREADY
	//existed on the server exactly as it was (ignores any change the client made to its roll
	//expression, even if it's smuggled in the same packet as the auto-populate), and only lets new
	//entries through (an itemId the server didn't already know about) — exactly what
	//CharacterSheetLoadProcedure.autoPopulateWeapons adds on its own, with the config's default
	//expression, never a hand-crafted one.
	private static JsonElement mergeAttacks(JsonElement serverSide, JsonElement clientSide) {
		if (!(clientSide instanceof JsonArray incomingArr)) return serverSide != null ? serverSide : new JsonArray();
		JsonArray serverArr = serverSide instanceof JsonArray arr ? arr : new JsonArray();

		Set<String> knownItemIds = new HashSet<>();
		JsonArray merged = new JsonArray();
		for (JsonElement el : serverArr) {
			merged.add(el);
			if (el.isJsonObject() && el.getAsJsonObject().has("itemId")) {
				knownItemIds.add(el.getAsJsonObject().get("itemId").getAsString());
			}
		}
		for (JsonElement el : incomingArr) {
			if (!el.isJsonObject() || !el.getAsJsonObject().has("itemId")) continue; //Without an itemId it's not a real auto-populated weapon: discard it.
			JsonObject clientForm = el.getAsJsonObject();
			String itemId = clientForm.get("itemId").getAsString();
			if (!knownItemIds.add(itemId)) continue;

			//The roll expression NEVER comes from the client: it's rebuilt here from the server's
			//config, same as autoPopulateWeapons. An itemId the config doesn't recognize as a weapon
			//has no trusted expression to offer, so it's discarded entirely.
			Config.WeaponDefault weaponDefault = Config.weaponDefaultFor(itemId);
			if (weaponDefault == null) continue;
			merged.add(trustedAttackEntry(clientForm, itemId, weaponDefault));
		}
		return merged;
	}

	private static JsonObject trustedAttackEntry(JsonObject clientForm, String itemId, Config.WeaponDefault weaponDefault) {
		JsonObject rollForm = new JsonObject();
		rollForm.addProperty("name", clientForm.has("name") ? clientForm.get("name").getAsString() : itemId);
		rollForm.addProperty("itemId", itemId);

		JsonObject roll = new JsonObject();
		roll.addProperty("context", "Damage");
		roll.addProperty("expression", weaponDefault.dice() + " + $" + weaponDefault.ability());

		JsonArray rollGroup = new JsonArray();
		rollGroup.add(roll);
		JsonArray rollSet = new JsonArray();
		rollSet.add(rollGroup);
		rollForm.add("rolls", rollSet);
		return rollForm;
	}
}
