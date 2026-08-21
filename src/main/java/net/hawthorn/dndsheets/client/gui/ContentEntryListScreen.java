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
		super(Component.translatable(specFor(type).titleKey()), parent);
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

	/**
	 * <p>Todo lo que este menu necesita saber de un tipo de contenido: como se titula, que campos pide su
	 * formulario, como se rellena desde una entrada existente y como vuelve a JSON.</p>
	 *
	 * <p>Eran CUATRO switch paralelos sobre el mismo enum, con los mismos casos en el mismo orden. Anadir
	 * un tipo obligaba a acordarse de tocar los cuatro, y olvidarse de uno no daba error de compilacion:
	 * daba un {@code IllegalStateException} en ejecucion, al abrir ese menu concreto.</p>
	 */
	private record FormSpec(String titleKey,
			List<ContentFormScreen.FieldSpec> fields,
			Function<JsonObject, Map<String, String>> prefill,
			Function<Map<String, String>, JsonObject> toJson) {
	}

	//TRAIT y MONSTER no tienen formulario plano: TRAIT usa TraitEditScreen (listas anidadas de nivel/dado)
	//y MONSTER todavia no tiene UI. Llevan solo titulo, y el switch los nombra en vez de dejarlos caer en
	//una rama default que lanzaba: asi el compilador obliga a decidir que hace un tipo NUEVO.
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
		for (JsonObject entry : entries) {
			String id = entry.has("id") ? entry.get("id").getAsString() : "?";
			String name = entry.has("name") ? entry.get("name").getAsString() : id;
			addRow(Component.literal(id.equals(name) ? id : id + " — " + name), b -> openEditor(entry));
		}
		addRow(Component.translatable("gui.dndsheets.content_entry.add"), b -> openCreateForm());
	}

	//TRAIT tiene sus propias listas anidadas (nivel/dado) que no encajan en ContentFormScreen — ver
	//TraitEditScreen. El resto usa el formulario plano genérico.
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

	//Para TRAIT, "+ Añadir" solo pide id/nombre/característica (crea la entrada vacía de tablas de nivel);
	//las tablas se agregan editando la entrada recién creada desde TraitEditScreen.
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

	@Override
	protected Component emptyMessage() {
		return entries.isEmpty() ? Component.translatable("gui.dndsheets.content_entry.empty") : null;
	}
}
