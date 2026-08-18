package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.hawthorn.dndsheets.ContentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * <p>Lista las entradas de {@code dm_created.json} de un tipo (creador de contenido in-game): una fila por
 * entrada que abre su editor (donde vive el botón "Borrar", ver {@link SmallFormScreen#showDeleteButton()}),
 * más "+ Añadir" al final. Solo sabe de armas/hechizos/presets por ahora (ver {@code ContentTypeForms}) —
 * rasgos y monstruos usan flujos propios ({@code TraitEditScreen}, captura de plantilla desde
 * {@code MonsterActionScreen}), no este formulario genérico.</p>
 */
public class ContentEntryListScreen extends ListPickerScreen {
	private final ContentType type;
	private final List<JsonObject> entries;

	private ContentEntryListScreen(ContentType type, List<JsonObject> entries, Screen parent) {
		super(Component.literal(titleFor(type)), parent);
		this.type = type;
		this.entries = entries;
	}

	//Mismo criterio que DungeonPieceListScreen.open: el parent es lo que esté en pantalla en ese momento
	//(la pantalla que pidió la lista, o esta misma pantalla si es un eco tras guardar/borrar).
	public static void open(ContentType type, String arrayJson) {
		List<JsonObject> entries = new ArrayList<>();
		for (JsonElement el : JsonParser.parseString(arrayJson).getAsJsonArray()) entries.add(el.getAsJsonObject());
		Minecraft.getInstance().setScreen(new ContentEntryListScreen(type, entries, Minecraft.getInstance().screen));
	}

	private static String titleFor(ContentType type) {
		return switch (type) {
			case WEAPON -> "Armas creadas";
			case SPELL -> "Hechizos creados";
			case PRESET -> "Presets creados";
			case TRAIT -> "Rasgos creados";
			case MONSTER -> "Monstruos creados";
			case ENCOUNTER -> "Encuentros creados";
		};
	}

	private static List<ContentFormScreen.FieldSpec> fieldsFor(ContentType type) {
		return switch (type) {
			case WEAPON -> ContentTypeForms.weaponFields();
			case SPELL -> ContentTypeForms.spellFields();
			case PRESET -> ContentTypeForms.presetFields();
			case ENCOUNTER -> ContentTypeForms.encounterFields();
			default -> throw new IllegalStateException(type + " no usa ContentFormScreen");
		};
	}

	private static Function<JsonObject, Map<String, String>> prefillFor(ContentType type) {
		return switch (type) {
			case WEAPON -> ContentTypeForms::weaponPrefill;
			case SPELL -> ContentTypeForms::spellPrefill;
			case PRESET -> ContentTypeForms::presetPrefill;
			case ENCOUNTER -> ContentTypeForms::encounterPrefill;
			default -> throw new IllegalStateException(type + " no usa ContentFormScreen");
		};
	}

	private static Function<Map<String, String>, JsonObject> toJsonFor(ContentType type) {
		return switch (type) {
			case WEAPON -> ContentTypeForms::weaponToJson;
			case SPELL -> ContentTypeForms::spellToJson;
			case PRESET -> ContentTypeForms::presetToJson;
			case ENCOUNTER -> ContentTypeForms::encounterToJson;
			default -> throw new IllegalStateException(type + " no usa ContentFormScreen");
		};
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (JsonObject entry : entries) {
			String id = entry.has("id") ? entry.get("id").getAsString() : "?";
			String name = entry.has("name") ? entry.get("name").getAsString() : id;
			addRow(Component.literal(id.equals(name) ? id : id + " — " + name), b -> openEditor(entry));
		}
		addRow(Component.literal("+ Añadir"), b -> openCreateForm());
	}

	//TRAIT tiene sus propias listas anidadas (nivel/dado) que no encajan en ContentFormScreen — ver
	//TraitEditScreen. El resto usa el formulario plano genérico.
	private void openEditor(JsonObject entry) {
		String id = entry.get("id").getAsString();
		if (type == ContentType.TRAIT) {
			TraitEditScreen.open(entry);
		} else {
			ContentFormScreen.open(type, "Editar: " + id, fieldsFor(type), prefillFor(type).apply(entry), toJsonFor(type));
		}
	}

	//Para TRAIT, "+ Añadir" solo pide id/nombre/característica (crea la entrada vacía de tablas de nivel);
	//las tablas se agregan editando la entrada recién creada desde TraitEditScreen.
	private void openCreateForm() {
		if (type == ContentType.TRAIT) {
			ContentFormScreen.open(type, "Añadir rasgo", ContentTypeForms.traitCreateFields(), Map.of(), ContentTypeForms::traitCreateToJson);
		} else {
			ContentFormScreen.open(type, "Añadir " + titleFor(type).toLowerCase(java.util.Locale.ROOT), fieldsFor(type), Map.of(), toJsonFor(type));
		}
	}

	@Override
	protected Component emptyMessage() {
		return entries.isEmpty() ? Component.literal("Nada creado todavía in-game de este tipo.") : null;
	}
}
