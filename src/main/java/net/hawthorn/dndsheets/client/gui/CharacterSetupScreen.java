package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Los cuatro pasos que convierten una ficha en blanco en un personaje jugable, con lo que falta a la
 * vista: raza, clase (por preset, que además rellena características, dado de golpe y equipo), trasfondo y
 * competencias de habilidad.</p>
 *
 * <p><b>Es una lista de pasos, no un asistente que te encierre.</b> Cada fila abre la pantalla que ya
 * existía para ese paso y vuelve aquí al terminar, así que se puede hacer en cualquier orden, dejar a
 * medias y seguir otro día — que es como se rellena una ficha en una mesa real. Un asistente lineal habría
 * pedido, además, un camino de "atrás" propio para cada paso.</p>
 *
 * <p>Lo que aporta de verdad no es abrir pantallas —todas eran alcanzables desde la hoja— sino
 * <b>decir cuáles faltan</b>. Un jugador nuevo abre su ficha en blanco y no tiene forma de saber que hay
 * cuatro cosas que elegir ni dónde están; esa es la misma clase de fallo que el punto 40, un paso más
 * arriba: no es que el estado no se vea, es que la tarea no se ve.</p>
 *
 * <p>Se lee de la hoja del cliente, que ya está sincronizada, así que no hace falta ningún mensaje nuevo:
 * las filas se repintan cuando llega la hoja del servidor (ver {@link #refreshIfOpen}).</p>
 */
public class CharacterSetupScreen extends ListPickerScreen {

	private CharacterSetupScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.character_setup.title"), parent);
	}

	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new CharacterSetupScreen(parent));
	}

	public static void refreshIfOpen() {
		if (Minecraft.getInstance().screen instanceof CharacterSetupScreen screen) {
			screen.rebuildWidgets();
		}
	}

	@Override
	protected void buildRows() {
		JsonObject sheet = SheetLoader.getClientSheet();

		//La raza la elige Origins (ver Modularity Map / dndsheets_species), no un picker propio: este botón
		//abre el selector real de Origins y sincroniza solo, unos segundos después (ver SpeciesCommand.choose).
		addRow(step("gui.dndsheets.character_setup.race", field(sheet, "characterRace")),
			button -> openOriginPicker("dndspecies choose"));

		//La clase también la elige Origins (capa origins-classes:class, reemplazada entera con las 12 clases
		//del SRD — ver Modularity Map / dndsheets_species): mismo patrón que Raza, y sigue aplicando el
		//PRESET real (dado de golpe, características, equipo inicial, rasgos), no solo el nombre.
		addRow(step("gui.dndsheets.character_setup.class", field(sheet, "characterClass")),
			button -> openOriginPicker("dndspecies chooseclass"));

		//El trasfondo lo elige Origins también (ver Modularity Map / dndsheets_species), mismo patrón que Raza.
		addRow(step("gui.dndsheets.character_setup.background", field(sheet, "background")),
			button -> openOriginPicker("dndspecies choosebackground"));

		//La subclase solo aparece cuando ya se puede elegir: enseñar un paso bloqueado a un personaje de
		//nivel 1 es prometerle algo que la pantalla luego le niega. Que se pueda o no lo sabe el servidor
		//(preset y nivel), así que la fila se ofrece siempre que haya clase y él decide si hay lista.
		if (!field(sheet, "characterClass").isBlank()) {
			addRow(step("gui.dndsheets.character_setup.subclass", field(sheet, "characterSubclass")),
				button -> DndsheetsMod.PACKET_HANDLER.sendToServer(
					new BrowseActionMessage(BrowseActionMessage.Action.LIST_SUBCLASSES)));
		}

		int proficiencies = 0;
		for (int index = 0; index < RollIndex.SKILL_COUNT; index++) {
			if (RollIndex.isSkillProficient(sheet, index)) proficiencies++;
		}
		addRow(step("gui.dndsheets.character_setup.skills", proficiencies == 0 ? "" : Component.translatable("gui.dndsheets.character_setup.skills_marked", proficiencies).getString()),
			button -> SkillProficiencyScreen.open(this));
	}

	/** Un paso con lo que ya tiene puesto, o en gris con un "—" si sigue sin elegir. */
	private static Component step(String name, String value) {
		return value.isBlank()
			? Component.literal(name + ": —").withStyle(ChatFormatting.GRAY)
			: Component.literal(name + ": " + value);
	}

	private static String field(JsonObject sheet, String key) {
		return sheet != null && sheet.has(key) && sheet.get(key).isJsonPrimitive()
			? sheet.get(key).getAsString() : "";
	}

	//Cierra esta pantalla ANTES de que llegue el selector de Origins: dejarla abierta encima le robaba el
	//clic/teclado al selector, que quedaba inutilizable hasta cerrar la ficha a mano.
	private static void openOriginPicker(String command) {
		Minecraft.getInstance().player.connection.sendCommand(command);
		Minecraft.getInstance().setScreen(null);
	}
}
