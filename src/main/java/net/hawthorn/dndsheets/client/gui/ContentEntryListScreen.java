package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nullable;
import net.hawthorn.dndsheets.ContentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * <p>Manages the content of one type: one row per entry that opens its editor (where the "Delete" button
 * lives, see {@link SmallFormScreen#showDeleteButton()}), plus "+ Add". It only knows about weapons/spells/
 * presets/feats/encounters (see {@code ContentTypeForms}) — traits and monsters use their own flows
 * ({@code TraitEditScreen}, template capture from {@code MonsterActionScreen}).</p>
 *
 * <p><b>Two sections, not one.</b> On top, what the DM created ({@code dm_created.json}); below, what the
 * pack provides. It used to show only the former, so opening "Encounters" without having created any gave
 * an empty screen despite having five loaded, playable encounters: it reads as if this manages nothing.
 * Editing a pack entry saves YOUR version with the same id in your file, and that's the one that wins on
 * load (see the order in {@code DndPaths.autoLoadAll}) — the pack itself isn't touched, since it's rewritten
 * from the jar on every startup.</p>
 */
public class ContentEntryListScreen extends ListPickerScreen {
	private final ContentType type;
	private final List<JsonObject> entries;
	private final List<JsonObject> fromPacks;

	private ContentEntryListScreen(ContentType type, List<JsonObject> entries, List<JsonObject> fromPacks, Screen parent) {
		super(Component.translatable(specFor(type).titleKey()), parent);
		this.type = type;
		this.entries = entries;
		this.fromPacks = fromPacks;
	}

	//Same criterion as DungeonPieceListScreen.open: the parent is whatever's on screen at that moment
	//(the screen that requested the list, or this same screen if it's an echo after saving/deleting).
	public static void open(ContentType type, String arrayJson, String packsJson) {
		Minecraft.getInstance().setScreen(new ContentEntryListScreen(type, parse(arrayJson), parse(packsJson),
			Minecraft.getInstance().screen));
	}

	private static List<JsonObject> parse(String arrayJson) {
		List<JsonObject> entries = new ArrayList<>();
		for (JsonElement el : JsonParser.parseString(arrayJson).getAsJsonArray()) entries.add(el.getAsJsonObject());
		return entries;
	}

	/**
	 * <p>Everything this menu needs to know about a content type: what its title is, what fields its
	 * form asks for, how to prefill it from an existing entry, and how to turn it back into JSON.</p>
	 *
	 * <p>This used to be FOUR parallel switches over the same enum, with the same cases in the same order.
	 * Adding a type meant remembering to touch all four, and forgetting one didn't give a compile error:
	 * it gave an {@code IllegalStateException} at runtime, when opening that particular menu.</p>
	 */
	private record FormSpec(String titleKey,
			List<ContentFormScreen.FieldSpec> fields,
			Function<JsonObject, Map<String, String>> prefill,
			Function<Map<String, String>, JsonObject> toJson) {
	}

	//TRAIT and MONSTER have no flat form: TRAIT uses TraitEditScreen (nested level/die tables)
	//and MONSTER doesn't have a UI yet. They carry only a title, and the switch names them explicitly
	//instead of letting them fall into a default branch that throws: this way the compiler forces a
	//decision about what a NEW type does.
	private static FormSpec specFor(ContentType type) {
		return switch (type) {
			case WEAPON -> new FormSpec("gui.dndsheets.content_entry.weapons",
				ContentTypeForms.weaponFields(), ContentTypeForms::weaponPrefill, ContentTypeForms::weaponToJson);
			case SPELL -> new FormSpec("gui.dndsheets.content_entry.spells",
				ContentTypeForms.spellFields(), ContentTypeForms::spellPrefill, ContentTypeForms::spellToJson);
			case PRESET -> new FormSpec("gui.dndsheets.content_entry.presets",
				ContentTypeForms.presetFields(), ContentTypeForms::presetPrefill, ContentTypeForms::presetToJson);
			case ENCOUNTER -> new FormSpec("gui.dndsheets.content_entry.encounters",
				ContentTypeForms.encounterFields(), ContentTypeForms::encounterPrefill, ContentTypeForms::encounterToJson);
			case FEAT -> new FormSpec("gui.dndsheets.content_entry.feats",
				ContentTypeForms.featFields(), ContentTypeForms::featPrefill, ContentTypeForms::featToJson);
			case TRAIT -> new FormSpec("gui.dndsheets.content_entry.traits", null, null, null);
			case MONSTER -> new FormSpec("gui.dndsheets.content_entry.monsters", null, null, null);
		};
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (JsonObject entry : entries) addEntryRow(entry);
		addRow(Component.translatable("gui.dndsheets.content_entry.add"), b -> openCreateForm());

		if (fromPacks.isEmpty()) return;
		addHeader(Component.translatable("gui.dndsheets.content_entry.from_pack"));
		//Ids you already have your own version of aren't repeated below: your version is the one that
		//counts, and showing it twice — one editable, one not — only invites editing the one that doesn't count.
		java.util.Set<String> mine = new java.util.HashSet<>();
		for (JsonObject entry : entries) if (entry.has("id")) mine.add(entry.get("id").getAsString());
		for (JsonObject entry : fromPacks) {
			if (entry.has("id") && mine.contains(entry.get("id").getAsString())) continue;
			addEntryRow(entry);
		}
	}

	private void addEntryRow(JsonObject entry) {
		String id = entry.has("id") ? entry.get("id").getAsString() : "?";
		String name = entry.has("name") ? entry.get("name").getAsString() : id;
		//A pack name is a language key ("content.dndsheets.encounter.crypt_guardians"): it gets translated, which
		//is what the DM recognizes. Whatever the DM types is their own text and ContentNames leaves it as-is.
		Component label = Component.literal(id + " — ").append(net.hawthorn.dndsheets.ContentNames.of(name));
		addRow(id.equals(name) ? Component.literal(id) : label, b -> openEditor(entry));
	}

	//TRAIT has its own nested lists (level/die) that don't fit ContentFormScreen — see
	//TraitEditScreen. Everything else uses the generic flat form.
	private void openEditor(JsonObject entry) {
		String id = entry.get("id").getAsString();
		if (type == ContentType.TRAIT) {
			TraitEditScreen.open(entry);
		} else {
			FormSpec spec = specFor(type);
			ContentFormScreen.open(type, Component.translatable("gui.dndsheets.content_entry.edit", id).getString(),
				spec.fields(), spec.prefill().apply(entry), spec.toJson());
		}
	}

	//For TRAIT, "+ Add" only asks for id/name/characteristic (it creates the entry with empty level
	//tables); the tables get added by editing the newly created entry from TraitEditScreen.
	private void openCreateForm() {
		if (type == ContentType.TRAIT) {
			ContentFormScreen.open(type, Component.translatable("gui.dndsheets.content_entry.add_trait").getString(),
				ContentTypeForms.traitCreateFields(), Map.of(), ContentTypeForms::traitCreateToJson);
		} else {
			FormSpec spec = specFor(type);
			ContentFormScreen.open(type,
				Component.translatable("gui.dndsheets.content_entry.add_to",
					Component.translatable(spec.titleKey()).getString().toLowerCase(java.util.Locale.ROOT)).getString(),
				spec.fields(), Map.of(), spec.toJson());
		}
	}

	@Nullable
	@Override
	protected Component emptyMessage() {
		return entries.isEmpty() && fromPacks.isEmpty()
			? Component.translatable("gui.dndsheets.content_entry.empty") : null;
	}
}
