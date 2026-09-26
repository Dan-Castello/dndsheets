package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.ContentType;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.ContentEntryRemoveMessage;
import net.hawthorn.dndsheets.network.ContentEntrySaveMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Editor for ONE already-created trait (see {@code ContentEntryListScreen}, which opens this screen
 * instead of {@link ContentFormScreen} for the TRAIT type): unlike weapons/spells/presets, a trait has
 * two nested level/dice lists ({@code unarmedDiceByLevel}/{@code sneakAttackDiceByLevel}, see
 * {@code TraitRegistry}) that don't fit into a flat, single-column form.</p>
 *
 * <p>Every action (editing name/ability, adding or deleting a level) rebuilds the ENTIRE entry on the
 * client and sends it whole via {@code ContentEntrySaveMessage} — the server doesn't know how to
 * "patch" a single field, only save/replace the entire entry (same rule as
 * {@code ContentPackFile.upsert}). It reopens this same screen with the updated local copy instantly
 * instead of waiting for the server's echo, so the DM isn't bounced back to the general list on every
 * level added/deleted.</p>
 */
public class TraitEditScreen extends ListPickerScreen {
	private final JsonObject entry;
	private final String id;

	private TraitEditScreen(JsonObject entry, Screen parent) {
		super(Component.translatable("gui.dndsheets.trait_edit.title", entry.get("id").getAsString()), parent);
		this.entry = entry;
		this.id = entry.get("id").getAsString();
	}

	public static void open(JsonObject entry) {
		Minecraft.getInstance().setScreen(new TraitEditScreen(entry, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildRows() {
		String name = entry.has("name") ? entry.get("name").getAsString() : id;
		String unarmedAbility = entry.has("unarmedAbility") ? entry.get("unarmedAbility").getAsString() : "str";
		addRow(Component.translatable("gui.dndsheets.trait_edit.name", name), b -> TraitBasicInfoScreen.open(entry));
		addRow(Component.translatable("gui.dndsheets.trait_edit.unarmed", unarmedAbility), b -> TraitBasicInfoScreen.open(entry));

		addTierRows("unarmedDiceByLevel", Component.translatable("gui.dndsheets.trait_edit.tier_martial"));
		addRow(Component.translatable("gui.dndsheets.trait_edit.add_martial"), b -> TierAddScreen.open(entry, "unarmedDiceByLevel"));

		addTierRows("sneakAttackDiceByLevel", Component.translatable("gui.dndsheets.trait_edit.tier_sneak"));
		addRow(Component.translatable("gui.dndsheets.trait_edit.add_sneak"), b -> TierAddScreen.open(entry, "sneakAttackDiceByLevel"));

		addRow(Component.translatable("gui.dndsheets.trait_edit.delete"), b -> ConfirmScreen.ask(Component.literal(id), () -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new ContentEntryRemoveMessage(ContentType.TRAIT, id));
			this.onClose();
		}));
	}

	private void addTierRows(String field, Component label) {
		if (!entry.has(field)) return;
		for (JsonElement el : entry.getAsJsonArray(field)) {
			JsonObject tier = el.getAsJsonObject();
			addRow(Component.translatable("gui.dndsheets.trait_edit.delete_tier", label, tier.get("level").getAsInt(), tier.get("dice").getAsString()),
				b -> ConfirmScreen.ask(Component.translatable("gui.dndsheets.trait_edit.tier_name", label, tier.get("level").getAsInt()),
					() -> removeTier(field, tier)));
		}
	}

	private void removeTier(String field, JsonObject tierToRemove) {
		JsonObject updated = entry.deepCopy();
		JsonArray kept = new JsonArray();
		for (JsonElement el : updated.getAsJsonArray(field)) {
			JsonObject tier = el.getAsJsonObject();
			boolean matches = tier.get("level").getAsInt() == tierToRemove.get("level").getAsInt()
				&& tier.get("dice").getAsString().equals(tierToRemove.get("dice").getAsString());
			if (!matches) kept.add(tier);
		}
		updated.add(field, kept);
		save(updated);
	}

	static void save(JsonObject updated) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new ContentEntrySaveMessage(ContentType.TRAIT, updated.toString()));
		TraitEditScreen.open(updated);
	}
}
