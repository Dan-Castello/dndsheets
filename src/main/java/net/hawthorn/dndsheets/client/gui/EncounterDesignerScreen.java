package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.hawthorn.dndsheets.ContentType;
import net.hawthorn.dndsheets.EncounterBudget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <p>Diseñador de encuentros: se arma el grupo eligiendo monstruos de una lista y, a cada clic, la
 * pantalla dice qué tan dura le queda la pelea al grupo conectado ("Media", "Mortal"). Antes un encuentro
 * se escribía a mano en una casilla de texto ({@code ContentTypeForms.encounterFields}) sin ninguna
 * respuesta sobre si eran cuatro goblins o cuatro dragones: componer ya se podía, <em>calibrar</em> no.</p>
 *
 * <p>El bestiario, el coste en PX de cada monstruo y los umbrales del grupo llegan de una sola vez con la
 * pantalla (ver {@code BrowseActionMessage.DESIGN_ENCOUNTER}): la cuenta de {@link EncounterBudget} es
 * aritmética sobre esos números, así que sumar un goblin no necesita preguntarle nada al servidor.</p>
 *
 * <p><b>Guardar no guarda aquí.</b> Al pulsar se abre el formulario de contenido de siempre con la
 * composición ya escrita: el id, el nombre y la escritura en {@code dm_created.json} siguen viviendo en
 * {@link ContentFormScreen}/{@code ContentEntrySaveMessage}, que ya lo hacían bien para los cinco tipos de
 * contenido. Esta pantalla solo aporta la parte que faltaba, elegir con criterio.</p>
 */
public class EncounterDesignerScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<Component> names;
	private final int[] xp;
	private final int[] thresholds;
	private final int partySize;
	//id del monstruo -> cuántos. Ordenado por inserción: la lista se lee en el orden en que el DM la armó.
	private final Map<String, Integer> chosen = new LinkedHashMap<>();

	private EncounterDesignerScreen(List<String> ids, List<Component> names, JsonObject payload, Screen parent) {
		super(Component.translatable("gui.dndsheets.encounter_designer.title"), parent);
		this.ids = ids;
		this.names = names;
		this.xp = intArray(payload, "xp", ids.size());
		this.thresholds = intArray(payload, "t", 4);
		this.partySize = payload.has("p") ? payload.get("p").getAsInt() : 0;
	}

	public static void open(List<String> ids, List<Component> names, String payloadJson) {
		JsonObject payload;
		try {
			payload = JsonParser.parseString(payloadJson).getAsJsonObject();
		} catch (RuntimeException e) {
			return; //Carga corrupta o versión cruzada: sin presupuesto no hay diseñador que enseñar.
		}
		Minecraft.getInstance().setScreen(
			new EncounterDesignerScreen(ids, names, payload, Minecraft.getInstance().screen));
	}

	private static int[] intArray(JsonObject payload, String key, int size) {
		int[] values = new int[size];
		if (!payload.has(key)) return values;
		List<JsonElement> elements = payload.getAsJsonArray(key).asList();
		for (int i = 0; i < Math.min(size, elements.size()); i++) values[i] = elements.get(i).getAsInt();
		return values;
	}

	//Sitio para el renglón del veredicto, que es lo que se mira entre clic y clic.
	@Override
	protected int listTop() {
		return super.listTop() + 12;
	}

	@Override
	protected void buildRows() {
		for (Map.Entry<String, Integer> member : chosen.entrySet()) {
			String id = member.getKey();
			int count = member.getValue();
			int index = ids.indexOf(id);
			Component name = index < 0 ? Component.literal(id) : names.get(index);
			addRow(Component.translatable("gui.dndsheets.encounter_designer.row", name, count, xpOf(id) * count),
				//Clic suma uno y mayús+clic quita uno (al llegar a cero, fuera): un botón por fila en vez de
				//tres, que en una lista de ancho fijo es la diferencia entre leer el nombre y no leerlo.
				b -> {
					int updated = count + (hasShiftDown() ? -1 : 1);
					if (updated <= 0) chosen.remove(id);
					else chosen.put(id, updated);
					rebuildWidgets();
				});
		}

		addRow(Component.translatable("gui.dndsheets.encounter_designer.add"),
			b -> MonsterPickScreen.open(this, ids, names, id -> {
				chosen.merge(id, 1, Integer::sum);
				rebuildWidgets();
			}));

		if (!chosen.isEmpty()) {
			addRow(Component.translatable("gui.dndsheets.encounter_designer.save"), b -> save());
		}

		//Editar o borrar lo ya guardado se hacía solo desde "Crear contenido", que es donde nadie va a
		//buscarlo: quien acaba de diseñar un encuentro está acá, no tres menús más allá.
		addRow(Component.translatable("gui.dndsheets.encounter_designer.manage"),
			b -> net.hawthorn.dndsheets.DndsheetsMod.PACKET_HANDLER.sendToServer(
				new net.hawthorn.dndsheets.network.BrowseActionMessage(
					net.hawthorn.dndsheets.network.BrowseActionMessage.Action.CONTENT_ENTRIES,
					ContentType.ENCOUNTER.name())));
	}

	@Override
	protected Component emptyMessage() {
		return null; //Nunca está vacía: "+ Añadir monstruo" siempre está.
	}

	/** La composición en la misma sintaxis del JSON y del formulario: "dndsheets:goblin x4, dndsheets:wolf x2". */
	private String composition() {
		StringBuilder text = new StringBuilder();
		for (Map.Entry<String, Integer> member : chosen.entrySet()) {
			if (text.length() > 0) text.append(", ");
			text.append(member.getKey());
			if (member.getValue() > 1) text.append(" x").append(member.getValue());
		}
		return text.toString();
	}

	private void save() {
		ContentFormScreen.open(ContentType.ENCOUNTER,
			Component.translatable("gui.dndsheets.encounter_designer.save").getString(),
			ContentTypeForms.encounterFields(), Map.of("monsters", composition()),
			ContentTypeForms::encounterToJson);
	}

	private int xpOf(String monsterId) {
		int index = ids.indexOf(monsterId);
		return index < 0 ? 0 : xp[index];
	}

	private int totalXp() {
		int total = 0;
		for (Map.Entry<String, Integer> member : chosen.entrySet()) total += xpOf(member.getKey()) * member.getValue();
		return total;
	}

	private int totalMonsters() {
		int total = 0;
		for (int count : chosen.values()) total += count;
		return total;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.render(guiGraphics, mouseX, mouseY, partialTicks);

		int rating = EncounterBudget.rate(totalXp(), totalMonsters(), partySize, thresholds);
		Component summary;
		if (chosen.isEmpty()) {
			//Antes de elegir nada, la fila dice cómo se usa la pantalla; el veredicto de un encuentro vacío
			//("Trivial") no es información, es ruido.
			summary = Component.translatable("gui.dndsheets.encounter_designer.hint");
		} else if (rating < 0) {
			summary = Component.translatable("gui.dndsheets.encounter_designer.no_party", totalXp());
		} else {
			summary = Component.translatable("gui.dndsheets.encounter_designer.summary",
				net.hawthorn.dndsheets.network.BrowseActionMessage.difficultyName(rating), totalXp(), partySize);
		}
		guiGraphics.drawCenteredString(this.font, summary, this.width / 2, super.listTop(), GuiStyle.SUBTITLE_COLOR);
	}

	/** El bestiario para elegir uno; vuelve al diseñador con lo elegido (y con lo que ya llevaba puesto). */
	private static class MonsterPickScreen extends ListPickerScreen {
		private final List<String> ids;
		private final List<Component> names;
		private final Consumer<String> onPick;

		private MonsterPickScreen(Screen parent, List<String> ids, List<Component> names, Consumer<String> onPick) {
			super(Component.translatable("gui.dndsheets.encounter_designer.pick"), parent);
			this.ids = ids;
			this.names = names;
			this.onPick = onPick;
		}

		static void open(Screen parent, List<String> ids, List<Component> names, Consumer<String> onPick) {
			Minecraft.getInstance().setScreen(new MonsterPickScreen(parent, ids, names, onPick));
		}

		@Override
		protected void buildRows() {
			for (int i = 0; i < ids.size(); i++) {
				String id = ids.get(i);
				addRow(i < names.size() ? names.get(i) : Component.literal(id), b -> {
					onPick.accept(id);
					this.onClose(); //Vuelve al diseñador, que conserva lo ya elegido.
				});
			}
		}
	}
}
