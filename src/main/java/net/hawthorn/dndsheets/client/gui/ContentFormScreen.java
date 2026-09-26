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
	public enum FieldKind { TEXT, INT, CYCLE, PICK }

	public record FieldSpec(String key, String label, FieldKind kind, String defaultValue, String[] cycleOptions, int maxLength,
							String source, boolean multi) {
		public static FieldSpec text(String key, String label, String defaultValue) {
			return text(key, label, defaultValue, 64);
		}

		/** With its own cap: for fields holding a comma-separated LIST, where 64 falls short. */
		public static FieldSpec text(String key, String label, String defaultValue, int maxLength) {
			return new FieldSpec(key, label, FieldKind.TEXT, defaultValue, null, maxLength, null, false);
		}

		/** A registry item id: a text box plus a button that opens the icon picker ({@link ItemPickerScreen}). */
		public static FieldSpec item(String key, String label, String defaultValue) {
			return pick(key, label, defaultValue, "ITEM", false, 64);
		}

		/**
		 * A text box (still editable by hand) plus a "..." button that opens {@link ChoiceScreen} for {@code source}
		 * (ITEM, SPELL, TRAIT, MONSTER, WEAPON, MAGIC_ITEM, CLASS, ENCHANTMENT, CONDITION, EFFECT, CREATURE_TYPE,
		 * DAMAGE_AFFINITY). {@code multi} = the box holds a comma-separated list.
		 */
		public static FieldSpec pick(String key, String label, String defaultValue, String source, boolean multi, int maxLength) {
			return new FieldSpec(key, label, FieldKind.PICK, defaultValue, null, maxLength, source, multi);
		}

		public static FieldSpec intField(String key, String label, String defaultValue) {
			return new FieldSpec(key, label, FieldKind.INT, defaultValue, null, 8, null, false);
		}

		public static FieldSpec cycle(String key, String label, String[] options) {
			return new FieldSpec(key, label, FieldKind.CYCLE, options[0], options, 0, null, false);
		}
	}

	//A form of up to SINGLE_PAGE_MAX fields stays on ONE page (weapon, preset... as always); a longer one (spell with
	//its effect/area fields, magic item) splits in pages of PAGE_SIZE with a button to flip. 8 + the page button +
	//Confirm is as tall as the old 10-field form, so it fits wherever that one did.
	private static final int SINGLE_PAGE_MAX = 10;
	private static final int PAGE_SIZE = 8;

	private final ContentType type;
	private final List<FieldSpec> fields;
	private final Map<String, String> prefill;
	private final Function<Map<String, String>, JsonObject> toJson;
	//The entry as it was before editing (null when creating): whatever the form doesn't manage is kept.
	private final JsonObject original;
	private int page = 0;

	private final Map<String, EditBox> textBoxes = new LinkedHashMap<>();
	private final Map<String, CycleField> cycleFields = new LinkedHashMap<>();
	//What was typed so far: survives the rebuilds (picker return, page flip), which would otherwise reset
	//every field to its prefill.
	private final Map<String, String> current = new LinkedHashMap<>();

	private ContentFormScreen(ContentType type, String title, List<FieldSpec> fields, Map<String, String> prefill,
			Function<Map<String, String>, JsonObject> toJson, JsonObject original, Screen parent) {
		super(Component.literal(title), Math.max(1, (Math.min(fields.size(), fields.size() <= SINGLE_PAGE_MAX ? fields.size() : PAGE_SIZE + 1) + 2) / 2), parent);
		this.type = type;
		this.fields = fields;
		this.prefill = prefill;
		this.toJson = toJson;
		this.original = original;
	}

	/** Empty {@code prefill} = blank form (create); with data = edit, see {@code ContentTypeForms}. */
	public static void open(ContentType type, String title, List<FieldSpec> fields, Map<String, String> prefill,
			Function<Map<String, String>, JsonObject> toJson) {
		open(type, title, fields, prefill, toJson, null);
	}

	/** Editing: {@code original} is the entry being edited, so the keys the form doesn't manage survive the save. */
	public static void open(ContentType type, String title, List<FieldSpec> fields, Map<String, String> prefill,
			Function<Map<String, String>, JsonObject> toJson, JsonObject original) {
		Minecraft.getInstance().setScreen(new ContentFormScreen(type, title, fields, prefill, toJson, original, Minecraft.getInstance().screen));
	}

	//The id can't be edited once created: ContentEntrySaveMessage upserts by id, so changing it in
	//an EDIT form would leave the old entry orphaned in dm_created.json instead of renaming it.
	//What marks "edit" is already having an id, not having prefill data: the encounter designer
	//opens this form with the composition filled in and the id still to be set.
	private List<FieldSpec> editableFields() {
		List<FieldSpec> list = new java.util.ArrayList<>();
		for (FieldSpec field : fields) {
			if (!(field.key().equals("id") && prefill.containsKey("id"))) list.add(field);
		}
		return list;
	}

	private int pageSize() {
		int n = editableFields().size();
		return n <= SINGLE_PAGE_MAX ? Math.max(1, n) : PAGE_SIZE;
	}

	private int pageCount() {
		return Math.max(1, (editableFields().size() + pageSize() - 1) / pageSize());
	}

	@Override
	protected void buildForm() {
		textBoxes.clear();
		cycleFields.clear();
		List<FieldSpec> all = editableFields();
		int from = Math.min(page, pageCount() - 1) * pageSize();
		for (FieldSpec field : all.subList(from, Math.min(all.size(), from + pageSize()))) {
			String initial = current.getOrDefault(field.key(), prefill.getOrDefault(field.key(), field.defaultValue()));
			if (field.kind() == FieldKind.CYCLE) {
				int startIndex = Math.max(0, indexOf(field.cycleOptions(), initial));
				cycleFields.put(field.key(), addCycleButton(field.label(), field.cycleOptions(), field.cycleOptions(), startIndex));
			} else if (field.kind() == FieldKind.PICK) {
				int y = nextRowY();
				int left = centerX - formWidth() / 2;
				EditBox box = addFieldAt(field.label(), initial, field.maxLength(), y, left, formWidth() - 24);
				textBoxes.put(field.key(), box);
				this.addRenderableWidget(net.hawthorn.dndsheets.client.gui.components.TomeButton.of(Component.literal("..."), b -> {
					snapshot();
					ChoiceScreen.request(field.source(), field.multi(), textBoxes.get(field.key()).getValue(), text -> textBoxes.get(field.key()).setValue(text));
				}, left + formWidth() - 20, y, 20, FIELD_HEIGHT));
			} else {
				textBoxes.put(field.key(), addField(field.label(), initial, field.maxLength()));
			}
		}
		if (pageCount() > 1) {
			int y = nextRowY();
			this.addRenderableWidget(net.hawthorn.dndsheets.client.gui.components.TomeButton.of(
				Component.translatable("gui.dndsheets.form.page", page + 1, pageCount()), b -> {
					snapshot();
					page = (page + 1) % pageCount();
					this.rebuildWidgets();
				}, centerX - formWidth() / 2, y, formWidth(), FIELD_HEIGHT));
		}
	}

	private static int indexOf(String[] options, String value) {
		for (int i = 0; i < options.length; i++) {
			if (options[i].equalsIgnoreCase(value)) return i;
		}
		return 0;
	}

	//Prefill + defaults for every field, i.e. what the form holds before the DM touches anything.
	private Map<String, String> initialValues() {
		Map<String, String> values = new LinkedHashMap<>();
		if (prefill.containsKey("id")) values.put("id", prefill.get("id"));
		for (FieldSpec field : editableFields()) values.put(field.key(), prefill.getOrDefault(field.key(), field.defaultValue()));
		return values;
	}

	@Override
	protected void onConfirm() {
		snapshot();
		Map<String, String> values = initialValues();
		for (Map.Entry<String, String> entry : current.entrySet()) values.put(entry.getKey(), entry.getValue().trim());

		JsonObject entry = toJson.apply(values);
		if (!entry.has("id") || entry.get("id").getAsString().isBlank()) return;
		if (original != null) entry = mergeWithOriginal(entry);

		DndsheetsMod.PACKET_HANDLER.sendToServer(new ContentEntrySaveMessage(type, entry.toString()));
	}

	//Editing rewrites the whole entry, so anything the form has no field for (a spell's summon, a weapon's
	//custom model...) used to vanish silently — and editing an imported entry saves a copy that takes
	//precedence, so it silently broke the original. Keys the form owns (the ones it emits for the untouched
	//entry, plus its field keys) are replaced by the form's answer, which also lets a blanked field really
	//remove its key; every other key of the original is carried over as-is.
	private JsonObject mergeWithOriginal(JsonObject fromForm) {
		java.util.Set<String> owned = new java.util.HashSet<>(fromForm.keySet());
		for (FieldSpec field : fields) owned.add(field.key());
		try {
			owned.addAll(toJson.apply(initialValues()).keySet());
		} catch (RuntimeException ignored) {
			//A form that can't render the untouched entry only loses the "blank removes the key" nicety.
		}
		JsonObject merged = new JsonObject();
		for (Map.Entry<String, com.google.gson.JsonElement> kept : original.entrySet()) {
			if (!owned.contains(kept.getKey())) merged.add(kept.getKey(), kept.getValue());
		}
		for (Map.Entry<String, com.google.gson.JsonElement> own : fromForm.entrySet()) merged.add(own.getKey(), own.getValue());
		return merged;
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

	/** The item-id text boxes: drop targets for JEI's ghost drag (see compat/DndJeiPlugin). */
	public List<EditBox> itemBoxes() {
		List<EditBox> boxes = new java.util.ArrayList<>();
		for (FieldSpec field : fields) {
			EditBox box = field.kind() == FieldKind.PICK && "ITEM".equals(field.source()) && !field.multi() ? textBoxes.get(field.key()) : null;
			if (box != null) boxes.add(box);
		}
		return boxes;
	}

	/** The panel's column, so JEI keeps its item list clear of the form. */
	public net.minecraft.client.renderer.Rect2i panelArea() {
		return new net.minecraft.client.renderer.Rect2i(centerX - formWidth() / 2 - 12, 0, formWidth() + 24, this.height);
	}

	private void snapshot() {
		for (Map.Entry<String, EditBox> entry : textBoxes.entrySet()) current.put(entry.getKey(), entry.getValue().getValue());
		for (Map.Entry<String, CycleField> entry : cycleFields.entrySet()) current.put(entry.getKey(), entry.getValue().value());
	}
}
