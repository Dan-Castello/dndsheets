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
		//Sin el addon species instalado (el core solo también es una configuración soportada), cae al
		//selector de lista del propio mod — ver openOriginPicker.
		addRow(step("gui.dndsheets.character_setup.race", field(sheet, "characterRace")),
			button -> openOriginPicker("dndspecies choose", net.hawthorn.dndsheets.CharacterOptionsRegistry.RACE));

		//La clase también la elige Origins (capa origins-classes:class, reemplazada entera con las 12 clases
		//del SRD — ver Modularity Map / dndsheets_species): mismo patrón que Raza, y sigue aplicando el
		//PRESET real (dado de golpe, características, equipo inicial, rasgos), no solo el nombre.
		//Sin species, el respaldo de la clase NO es la lista de nombres sino el selector de presets: es
		//el mecanismo real del core (dado de golpe, características, equipo), no una etiqueta.
		addRow(step("gui.dndsheets.character_setup.class", field(sheet, "characterClass")),
			button -> {
				if (speciesLoaded()) openOriginPicker("dndspecies chooseclass");
				else DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.LIST_PRESETS));
			});

		//El trasfondo lo elige Origins también (ver Modularity Map / dndsheets_species), mismo patrón que Raza.
		addRow(step("gui.dndsheets.character_setup.background", field(sheet, "background")),
			button -> openOriginPicker("dndspecies choosebackground", net.hawthorn.dndsheets.CharacterOptionsRegistry.BACKGROUND));

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

	/** Un paso con su marca de progreso: "✔" verde si ya está elegido, "○" gris si falta. */
	private static Component step(String nameKey, String value) {
		//translatable(nameKey) y no literal: aquí llegaba la CLAVE ("gui.dndsheets.character_setup.race")
		//y se pintaba cruda en la fila — el único sitio del mod donde el jugador leía una clave de
		//traducción en pantalla.
		String name = Component.translatable(nameKey).getString();
		return value.isBlank()
			? Component.literal("○ " + name + ": —").withStyle(ChatFormatting.GRAY)
			: Component.literal("✔ ").withStyle(ChatFormatting.GREEN).append(Component.literal(name + ": " + value));
	}

	private static String field(JsonObject sheet, String key) {
		return sheet != null && sheet.has(key) && sheet.get(key).isJsonPrimitive()
			? sheet.get(key).getAsString() : "";
	}

	//Cierra esta pantalla ANTES de que llegue el selector de Origins: dejarla abierta encima le robaba el
	//clic/teclado al selector, que quedaba inutilizable hasta cerrar la ficha a mano. Sin el addon
	//species, el comando no existiría (error de Brigadier en el chat y ningún selector): quien llama
	//cae al selector de lista propio, que captura esta pantalla como padre y vuelve a ella al elegir.
	/** Público: también decide en los campos Raza/Clase/Trasfondo de la FICHA (CharacterSheetScreen),
	 *  que son la otra puerta al mismo viaje a Origins y tenían el mismo agujero sin el addon. */
	public static boolean speciesLoaded() {
		return net.minecraftforge.fml.ModList.get().isLoaded("dndsheets_species");
	}

	private void openOriginPicker(String command, String fallbackCategory) {
		if (speciesLoaded()) {
			openOriginPicker(command);
		} else {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(
				BrowseActionMessage.Action.CHARACTER_OPTIONS, fallbackCategory));
		}
	}

	private static void openOriginPicker(String command) {
		ReturnFromOrigins.expect();
		Minecraft.getInstance().player.connection.sendCommand(command);
		Minecraft.getInstance().setScreen(null);
	}

	/**
	 * <p>El viaje a Origins deja la pantalla en null a propósito (ver {@link #openOriginPicker}); esto
	 * trae al jugador DE VUELTA al checklist cuando el selector de Origins se cierra, en vez de dejarlo
	 * delante del mundo preguntándose qué sigue — con tres pasos que expulsan, era el momento de mayor
	 * abandono de la creación de personaje. El selector se reconoce por el paquete de su clase
	 * ({@code io.github.apace100} = Origins/Apoli): el core no compila contra Origins (esa dependencia
	 * es del addon species), así que el nombre es el único identificador disponible aquí.</p>
	 */
	@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
	public static class ReturnFromOrigins {
		private static boolean pending = false;

		static void expect() {
			pending = true;
		}

		@net.minecraftforge.eventbus.api.SubscribeEvent
		public static void onScreenClosed(net.minecraftforge.client.event.ScreenEvent.Closing event) {
			if (!pending) return;
			if (!event.getScreen().getClass().getName().startsWith("io.github.apace100")) return;
			//tell() y no setScreen directo: esto corre DENTRO del cierre de la otra pantalla, y navegar en
			//el mismo instante pisa el setScreen que la está cerrando. Un frame después, si Origins abrió
			//otra pantalla propia (flujos encadenados), se le cede el paso y se reintenta en su cierre.
			Minecraft.getInstance().tell(() -> {
				if (!pending || Minecraft.getInstance().screen != null) return;
				pending = false; //ponytail: si el jugador nunca vuelve a cerrar un selector de Origins, el flag queda armado hasta el siguiente; se acepta — limpiarlo exigiría rastrear toda navegación.
				CharacterSetupScreen.open(null);
			});
		}
	}
}
