package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.ContentType;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.ContentEntryRemoveMessage;
import net.hawthorn.dndsheets.network.ContentEntrySaveMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * <p>Generic form for content types whose schema is a flat object with no nested lists (weapons, spells,
 * presets — see {@code ContentTypeForms}): instead of a hand-built screen per type (like
 * {@code AddMonsterAttackScreen}/{@code AddTurnEffectScreen}), a single data-driven screen driven by a
 * list of {@link FieldSpec}. Traits (level/die lists) and monsters (attacks) don't fit here — see
 * {@code TraitEditScreen} and the template capture in {@code MonsterActionScreen}.</p>
 */
public class ContentFormScreen extends SmallFormScreen {
	public enum FieldKind { TEXT, INT, CYCLE }

	public record FieldSpec(String key, String label, FieldKind kind, String defaultValue, String[] cycleOptions, int maxLength) {
		public static FieldSpec text(String key, String label, String defaultValue) {
			return text(key, label, defaultValue, 64);
		}

		/** With its own cap: for fields holding a comma-separated LIST, where 64 falls short. */
		public static FieldSpec text(String key, String label, String defaultValue, int maxLength) {
			return new FieldSpec(key, label, FieldKind.TEXT, defaultValue, null, maxLength);
		}

		public static FieldSpec intField(String key, String label, String defaultValue) {
			return new FieldSpec(key, label, FieldKind.INT, defaultValue, null, 8);
		}

		public static FieldSpec cycle(String key, String label, String[] options) {
			return new FieldSpec(key, label, FieldKind.CYCLE, options[0], options, 0);
		}
	}

	private final ContentType type;
	private final List<FieldSpec> fields;
	private final Map<String, String> prefill;
	private final Function<Map<String, String>, JsonObject> toJson;

	private final Map<String, EditBox> textBoxes = new LinkedHashMap<>();
	private final Map<String, CycleField> cycleFields = new LinkedHashMap<>();

	private ContentFormScreen(ContentType type, String title, List<FieldSpec> fields, Map<String, String> prefill,
			Function<Map<String, String>, JsonObject> toJson, Screen parent) {
		super(Component.literal(title), Math.max(1, (fields.size() + 2) / 2), parent);
		this.type = type;
		this.fields = fields;
		this.prefill = prefill;
		this.toJson = toJson;
	}

	/** Empty {@code prefill} = blank form (create); with data = edit, see {@code ContentTypeForms}. */
	public static void open(ContentType type, String title, List<FieldSpec> fields, Map<String, String> prefill,
			Function<Map<String, String>, JsonObject> toJson) {
		Minecraft.getInstance().setScreen(new ContentFormScreen(type, title, fields, prefill, toJson, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		for (FieldSpec field : fields) {
			String initial = prefill.getOrDefault(field.key(), field.defaultValue());
			//The id can't be edited once created: ContentEntrySaveMessage upserts by id, so changing it in
			//an EDIT form would leave the old entry orphaned in dm_created.json instead of renaming it.
			//What marks "edit" is already having an id, not having prefill data: the encounter designer
			//opens this form with the composition filled in and the id still to be set.
			if (field.key().equals("id") && prefill.containsKey("id")) continue;
			if (field.kind() == FieldKind.CYCLE) {
				int startIndex = Math.max(0, indexOf(field.cycleOptions(), initial));
				cycleFields.put(field.key(), addCycleButton(field.label(), field.cycleOptions(), field.cycleOptions(), startIndex));
			} else {
				textBoxes.put(field.key(), addField(field.label(), initial, field.maxLength()));
			}
		}
	}

	private static int indexOf(String[] options, String value) {
		for (int i = 0; i < options.length; i++) {
			if (options[i].equalsIgnoreCase(value)) return i;
		}
		return 0;
	}

	@Override
	protected void onConfirm() {
		Map<String, String> values = new LinkedHashMap<>();
		if (prefill.containsKey("id")) values.put("id", prefill.get("id")); //Fixed during edit, see buildForm().
		for (Map.Entry<String, EditBox> entry : textBoxes.entrySet()) values.put(entry.getKey(), entry.getValue().getValue().trim());
		for (Map.Entry<String, CycleField> entry : cycleFields.entrySet()) values.put(entry.getKey(), entry.getValue().value());

		JsonObject entry = toJson.apply(values);
		if (!entry.has("id") || entry.get("id").getAsString().isBlank()) return;

		DndsheetsMod.PACKET_HANDLER.sendToServer(new ContentEntrySaveMessage(type, entry.toString()));
	}

	//Only when editing (there's an already-created id to delete) — when creating there's nothing to delete yet.
	@Override
	protected boolean showDeleteButton() {
		return prefill.containsKey("id");
	}

	@Override
	protected void onDelete() {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new ContentEntryRemoveMessage(type, prefill.get("id")));
	}
}
