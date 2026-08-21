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
 * <p>Editor de UN rasgo ya creado (ver {@code ContentEntryListScreen}, que abre esta pantalla en vez de
 * {@link ContentFormScreen} para el tipo TRAIT): a diferencia de armas/hechizos/presets, un rasgo tiene
 * dos listas de nivel/dado anidadas ({@code unarmedDiceByLevel}/{@code sneakAttackDiceByLevel}, ver
 * {@code TraitRegistry}) que no encajan en un formulario plano de una columna.</p>
 *
 * <p>Cada acción (editar nombre/característica, añadir o borrar un nivel) reconstruye la entrada
 * COMPLETA en el cliente y la manda entera por {@code ContentEntrySaveMessage} — el servidor no sabe
 * "parchear" un campo suelto, solo guardar/reemplazar la entrada entera (mismo criterio que
 * {@code ContentPackFile.upsert}). Reabre esta misma pantalla con la copia local actualizada al instante
 * en vez de esperar el eco del servidor, para no rebotar al DM a la lista general en cada nivel que
 * añade/borra.</p>
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

		addTierRows("unarmedDiceByLevel", "Artes marciales");
		addRow(Component.translatable("gui.dndsheets.trait_edit.add_martial"), b -> TierAddScreen.open(entry, "unarmedDiceByLevel"));

		addTierRows("sneakAttackDiceByLevel", "Ataque furtivo");
		addRow(Component.translatable("gui.dndsheets.trait_edit.add_sneak"), b -> TierAddScreen.open(entry, "sneakAttackDiceByLevel"));

		addRow(Component.translatable("gui.dndsheets.trait_edit.delete"), b -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new ContentEntryRemoveMessage(ContentType.TRAIT, id));
			this.onClose();
		});
	}

	private void addTierRows(String field, String label) {
		if (!entry.has(field)) return;
		for (JsonElement el : entry.getAsJsonArray(field)) {
			JsonObject tier = el.getAsJsonObject();
			addRow(Component.translatable("gui.dndsheets.trait_edit.delete_tier", label, tier.get("level").getAsInt(), tier.get("dice").getAsString()),
				b -> removeTier(field, tier));
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
