package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.client.gui.components.TomeButton;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.SpellRegistry;
import net.hawthorn.dndsheets.SpellSlots;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.hawthorn.dndsheets.network.SpellCastMessage;
import net.hawthorn.dndsheets.network.StaffBindMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Ventana aparte para lanzar hechizos conocidos, en vez de una 4ª pestaña en la hoja de personaje
 * (no hay textura de fondo para eso todavía). Se abre desde el botón "Grimorio" de la pestaña Main, o
 * directo con la tecla propia (ver {@code DndsheetsModKeyMappings.GRIMOIRE}) sin pasar por la ficha —no
 * depende de nada que solo viva ahí, lee todo de {@link SheetLoader#getClientSheet}. Apunta al objetivo
 * que estés mirando (ver {@link net.hawthorn.dndsheets.SpellCastManager}).</p>
 *
 * <p>El nombre, nivel y escuela de cada hechizo se leen directamente de la hoja (los guarda
 * {@code SpellRegistry.learn} junto al id), no de {@code SpellRegistry}: ese registro solo vive en memoria
 * del servidor, así que en un servidor dedicado de verdad (cliente y servidor son procesos distintos) el
 * cliente nunca tendría su propia copia y todo se vería como "desconocido".</p>
 *
 * <p><b>La escuela va dentro de la etiqueta de cada fila y no en un botón de filtro aparte.</b>
 * {@link ListPickerScreen} ya saca un buscador solo en cuanto la lista pasa de catorce filas, y ese
 * buscador filtra por el texto de la etiqueta: metiendo la escuela ahí, escribir "evoc" ya filtra por
 * escuela sin gastar una fila de la pantalla ni un widget nuevo. Lo mismo vale para "sin preparar".</p>
 */
public class GrimoireScreen extends ListPickerScreen {
	private static final int SUBTITLE_Y = 30;
	/** Dos filas: los niveles de espacio arriba, cuántos quedan de cada uno debajo. */
	private static final int SLOT_ROW_STEP = 10;
	/** Ancho de cada columna de la tabla de espacios: cabe "9/9" y los nueve niveles caben en el panel. */
	private static final int SLOT_COL_WIDTH = 24;
	/** Filas fijas bajo la lista: nivel de espacio, lanzar, preparar y vincular al báculo. */
	private static final int FIXED_ROWS = 4;

	/**
	 * <p>{@code label} es como sale en la LISTA —con nivel, escuela y si está sin preparar, que es lo que
	 * ordena, distingue y hace buscables las filas— y {@code name} a secas es lo que va en los botones,
	 * donde el nivel que importa es el del espacio elegido y no el suyo.</p>
	 */
	private record KnownSpell(String id, String name, String label, int level, boolean prepared) {}

	/**
	 * <p>El último hechizo lanzado desde aquí, para la tecla de lanzado rápido (ver
	 * {@code DndsheetsModKeyMappings.QUICK_CAST}). Estático y en el cliente a propósito: es una comodidad de
	 * quien juega, no estado de personaje — no merece un campo en la hoja ni un viaje por la red.</p>
	 */
	private static String lastCastId;
	private static int lastCastSlotLevel;

	private KnownSpell selected;
	private Button castButton;
	private Button slotLevelButton;
	private Button prepareButton;
	private Button bindButton;
	/** Nivel de espacio elegido para el hechizo seleccionado; 0 = el más bajo que sirva. */
	private int chosenSlotLevel;

	protected GrimoireScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.grimoire.title"), parent);
	}

	/** {@code parent} null cuando se abre suelto (tecla directa): sin "&lt; Atrás", Escape cierra a secas. */
	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new GrimoireScreen(parent));
	}

	/** Lanza otra vez lo último que se lanzó desde el Grimorio, sin abrirlo. Null-safe: sin nada previo, no hace nada. */
	public static void castLast() {
		if (lastCastId == null) return;
		DndsheetsMod.PACKET_HANDLER.sendToServer(new SpellCastMessage(lastCastId, lastCastSlotLevel));
	}

	@Override
	protected int buttonWidth() {
		return 220;
	}

	@Override
	protected int listTop() {
		return SUBTITLE_Y + SLOT_ROW_STEP + 14;
	}

	//Deja hueco fijo bajo la lista para las filas que no se desplazan con los hechizos.
	@Override
	protected int listHeight() {
		return super.listHeight() - FIXED_ROWS * (BUTTON_HEIGHT + SPACING);
	}

	@Override
	protected void init() {
		super.init();

		int left = (this.width - buttonWidth()) / 2;
		int slotY = listTop() + listHeight() + SPACING;
		int castY = slotY + BUTTON_HEIGHT + SPACING;
		int prepareY = castY + BUTTON_HEIGHT + SPACING;
		int bindY = prepareY + BUTTON_HEIGHT + SPACING;

		//Lanzar a nivel superior es una DECISIÓN, no algo que el servidor pueda adivinar: gastar un espacio
		//de 5º en una Bola de Fuego a cambio de más dados solo lo sabe quien lanza. Un botón que cicla en vez
		//de un desplegable porque los niveles posibles son pocos y contiguos.
		slotLevelButton = this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.grimoire.slot_level"), button -> {
			chosenSlotLevel = nextUsableLevel();
			updateButtons();
		}, left, slotY, buttonWidth(), BUTTON_HEIGHT));

		//Lanzar ya no pasa por un solo clic sobre el hechizo: un jugador que clica para leer qué hay en la
		//lista no quiere gastar un espacio de conjuro real por curiosidad.
		//Elegir un hechizo solo lo selecciona; este botón aparte es el que de verdad lo lanza.
		castButton = this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.grimoire.pick_spell"), button -> {
			if (selected == null) return;
			lastCastId = selected.id();
			lastCastSlotLevel = chosenSlotLevel;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new SpellCastMessage(selected.id(), chosenSlotLevel));
		}, left, castY, buttonWidth(), BUTTON_HEIGHT));

		//Preparar/despreparar. El límite lo pone y lo hace cumplir el SERVIDOR (ver
		//BrowseActionMessage.setPrepared): aquí solo se manda la intención, así que un cliente modificado no
		//se salta nada. La pantalla se repinta sola cuando llega la hoja actualizada.
		prepareButton = this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.grimoire.prepare_none"), button -> {
			if (selected == null || selected.level() <= 0) return;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(
				selected.prepared() ? BrowseActionMessage.Action.SPELL_UNPREPARE : BrowseActionMessage.Action.SPELL_PREPARE,
				selected.id()));
		}, left, prepareY, buttonWidth(), BUTTON_HEIGHT));

		//Con cientos de hechizos no es viable un báculo por cada uno (ver SpellCommand.buildStaffStack): el
		//que se entrega es reconfigurable, y este botón le reescribe el quickSpell al que llevas en la mano
		//principal en vez de crear un ítem nuevo. Solo activo si de verdad sostenés uno configurable.
		bindButton = this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.grimoire.bind_staff"), button -> {
			if (selected == null) return;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new StaffBindMessage(selected.id()));
		}, left, bindY, buttonWidth(), BUTTON_HEIGHT));

		updateButtons();
	}

	/**
	 * <p>Las filas van agrupadas por nivel bajo una cabecera ({@code addHeader}, no clicable y excluida del
	 * buscador). Con 87 hechizos posibles, una lista plana obliga a leerla entera para saber qué se puede
	 * lanzar con lo que queda; agrupada, el nivel se ve de un vistazo y coincide con la tabla de espacios
	 * que hay justo encima.</p>
	 */
	@Override
	protected void buildRows() {
		List<KnownSpell> spells = knownSpells();
		int lastLevel = -1;
		for (KnownSpell spell : spells) {
			if (spell.level() != lastLevel) {
				addHeader(spell.level() == 0
					? Component.translatable("gui.dndsheets.grimoire.cantrips")
					: Component.translatable("gui.dndsheets.grimoire.level", spell.level()));
				lastLevel = spell.level();
			}
			addRow(Component.literal(spell.label()), b -> { //i18n-ok: label() ya viene resuelta (ver knownSpells)
				selected = spell;
				//El nivel elegido se reinicia al del propio hechizo: heredar el "nv. 5" de la selección
				//anterior gastaría un espacio caro en el hechizo equivocado sin que nadie lo pidiera.
				chosenSlotLevel = spell.level();
				updateButtons();
			});
		}
	}

	private void updateButtons() {
		castButton.active = selected != null;
		//El nivel que se enseña aquí es el ELEGIDO, no el propio del hechizo. Enseñando el suyo, el botón
		//decía "Lanzar: Bola de Fuego (nv. 3)" justo debajo de "Espacio: nv. 5": dos números pegados que se
		//contradicen, y el equivocado en el botón que de verdad hace algo. Es además el mismo número que
		//sale luego en el chat, así que lo que se lee antes de pulsar y lo que se lee después coinciden.
		castButton.setMessage(selected == null ? Component.translatable("gui.dndsheets.grimoire.cast_none")
			: selected.level() == 0 ? Component.translatable("gui.dndsheets.grimoire.cast_cantrip", net.hawthorn.dndsheets.ContentNames.of(selected.name()))
			: Component.translatable("gui.dndsheets.grimoire.cast", net.hawthorn.dndsheets.ContentNames.of(selected.name()), chosenSlotLevel));

		//Un truco no gasta espacio, así que no hay nivel que elegir; y si no tienes ningún espacio por
		//encima del suyo, el botón no tiene a dónde ciclar.
		boolean canChoose = selected != null && selected.level() > 0 && nextUsableLevel() != chosenSlotLevel;
		slotLevelButton.active = canChoose;
		slotLevelButton.setMessage(
			selected == null ? Component.translatable("gui.dndsheets.grimoire.slot_none")
			: selected.level() == 0 ? Component.translatable("gui.dndsheets.grimoire.slot_cantrip")
			//"cambiar" y no "subir": el ciclo vuelve al nivel del hechizo al llegar arriba, y en el nivel
			//más alto disponible el único clic posible es precisamente el que baja.
			: canChoose ? Component.translatable("gui.dndsheets.grimoire.slot_change", chosenSlotLevel)
			: Component.translatable("gui.dndsheets.grimoire.slot_fixed", chosenSlotLevel));

		prepareButton.active = selected != null && selected.level() > 0;
		prepareButton.setMessage(
			selected == null ? Component.translatable("gui.dndsheets.grimoire.prepare_none")
			: selected.level() == 0 ? Component.translatable("gui.dndsheets.grimoire.prepare_cantrip")
			: selected.prepared() ? Component.translatable("gui.dndsheets.grimoire.unprepare", net.hawthorn.dndsheets.ContentNames.of(selected.name()))
			: Component.translatable("gui.dndsheets.grimoire.prepare", net.hawthorn.dndsheets.ContentNames.of(selected.name())));

		boolean holdingConfigurableStaff = isHoldingConfigurableStaff();
		bindButton.active = selected != null && holdingConfigurableStaff;
		bindButton.setMessage(
			!holdingConfigurableStaff ? Component.translatable("gui.dndsheets.grimoire.bind_none")
			: selected == null ? Component.translatable("gui.dndsheets.grimoire.bind_pick")
			: Component.translatable("gui.dndsheets.grimoire.bind_to", net.hawthorn.dndsheets.ContentNames.of(selected.name())));
	}

	//Solo se comprueba al construir los botones y al elegir un hechizo (ver init/buildRows), no cada
	//fotograma: cambiar de báculo con el Grimorio abierto no es un caso que valga la pena perseguir en vivo.
	private static boolean isHoldingConfigurableStaff() {
		return SpellRegistry.isConfigurableStaff(Minecraft.getInstance().player.getMainHandItem());
	}

	/**
	 * <p>Siguiente nivel de espacio con el que se puede lanzar el hechizo elegido, dando la vuelta al suyo
	 * al llegar arriba. Solo cuenta los que le QUEDAN al personaje: ofrecer un nivel vacío sería un clic que
	 * lleva a un lanzado que el servidor va a resolver con otro espacio distinto del que se ve en pantalla.</p>
	 */
	private int nextUsableLevel() {
		if (selected == null || selected.level() <= 0) return 0;
		JsonObject sheet = SheetLoader.getClientSheet();
		int[] current = sheet != null ? SpellSlots.currentSlots(sheet) : new int[SpellSlots.MAX_SPELL_LEVEL + 1];
		for (int step = 1; step <= SpellSlots.MAX_SPELL_LEVEL; step++) {
			//Recorre en círculo desde el nivel actual hasta volver a él, saltándose los agotados.
			int level = selected.level() + ((chosenSlotLevel - selected.level() + step) % (SpellSlots.MAX_SPELL_LEVEL + 1 - selected.level()));
			if (current[level] > 0) return level;
		}
		return chosenSlotLevel;
	}

	@Override
	protected Component emptyMessage() {
		return hasNoKnownSpells() ? Component.translatable("gui.dndsheets.grimoire.empty") : null;
	}

	//F9 del audit: evita reconstruir toda la lista de KnownSpell solo para saber si está vacía.
	private static boolean hasNoKnownSpells() {
		JsonObject sheet = SheetLoader.getClientSheet();
		return sheet == null || !sheet.has("spells") || sheet.getAsJsonArray("spells").isEmpty();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		renderSlotTable(guiGraphics);
	}

	/**
	 * <p>Los espacios por nivel, en columnas: el número de nivel arriba y cuántos quedan debajo.</p>
	 *
	 * <p>Aquí ponía un total plano ("Espacios: 5/9"), que desde que los espacios son por nivel de conjuro
	 * ya no responde a la pregunta que se hace quien mira esta pantalla. Un mago con 5 espacios sueltos no
	 * sabe si puede lanzar su Bola de Fuego: eso depende de si le queda alguno de 3º o más, y el total
	 * dice exactamente lo mismo tanto si le quedan cinco de nivel 1 como si le queda uno de nivel 5.</p>
	 */
	/** Una columna ya montada: el rótulo del nivel, el "quedan/máx" y su color. */
	private record SlotColumn(Component levelLabel, Component countLabel, int color) {}

	//La tabla solo cambia cuando cambia la hoja, pero renderSlotTable corre por FOTOGRAMA mientras el
	//Grimorio esté abierto: dos int[10] nuevos (readSlots hace 18 String.valueOf para buscar sus claves),
	//una List<Integer> con autoboxing y hasta 18 Component nuevos, todo para dibujar el mismo texto. Se
	//monta una vez por version de hoja, igual que ResourceHudOverlay.
	private int cachedSlotVersion = -1;
	private List<SlotColumn> cachedColumns;
	/** "Preparados: 5/8", o null para quien no es una clase lanzadora y no tiene lista que gestionar. */
	private Component cachedPrepared;

	private void rebuildSlotTable(JsonObject sheet) {
		int[] max = SpellSlots.maxSlotsOf(sheet);
		int[] current = SpellSlots.currentSlots(sheet);

		cachedColumns = new ArrayList<>();
		for (int level = 1; level <= SpellSlots.MAX_SPELL_LEVEL; level++) {
			if (max[level] <= 0) continue;
			//Un nivel agotado se apaga en vez de desaparecer: sigue ocupando su columna, así la tabla no se
			//reordena bajo el ratón cada vez que se gasta el último de un nivel.
			int color = current[level] > 0 ? GuiStyle.SUBTITLE_COLOR : GuiStyle.MUTED_COLOR;
			cachedColumns.add(new SlotColumn(
				Component.literal(level + "º"),
				Component.literal(current[level] + "/" + max[level]),
				color));
		}

		int limit = SpellRegistry.preparedLimitFor(sheet);
		cachedPrepared = limit <= 0 ? null
			: Component.translatable("gui.dndsheets.grimoire.prepared_count", SpellRegistry.preparedCount(sheet), limit);
	}

	private void renderSlotTable(GuiGraphics guiGraphics) {
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null) return;

		int version = SheetLoader.clientSheetVersion();
		if (version != cachedSlotVersion) {
			rebuildSlotTable(sheet);
			cachedSlotVersion = version;
		}

		if (cachedColumns.isEmpty()) {
			guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.grimoire.no_slots"), this.width / 2, SUBTITLE_Y, GuiStyle.MUTED_COLOR);
			return;
		}

		int firstColumnCenter = (this.width - cachedColumns.size() * SLOT_COL_WIDTH) / 2 + SLOT_COL_WIDTH / 2;
		for (int i = 0; i < cachedColumns.size(); i++) {
			SlotColumn column = cachedColumns.get(i);
			int x = firstColumnCenter + i * SLOT_COL_WIDTH;
			guiGraphics.drawCenteredString(this.font, column.levelLabel(), x, SUBTITLE_Y, GuiStyle.MUTED_COLOR);
			guiGraphics.drawCenteredString(this.font, column.countLabel(), x, SUBTITLE_Y + SLOT_ROW_STEP, column.color());
		}
		//A la derecha de la tabla, en la misma franja: cuántos lleva preparados de los que puede.
		if (cachedPrepared != null) {
			guiGraphics.drawString(this.font, cachedPrepared,
				firstColumnCenter + cachedColumns.size() * SLOT_COL_WIDTH, SUBTITLE_Y + SLOT_ROW_STEP / 2, GuiStyle.MUTED_COLOR, false);
		}
	}

	/** Ordenados por nivel (trucos primero) para que las cabeceras de {@link #buildRows} salgan solas. */
	private static List<KnownSpell> knownSpells() {
		List<KnownSpell> result = new ArrayList<>();
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null || !sheet.has("spells")) return result;

		JsonArray spells = sheet.getAsJsonArray("spells");
		for (int i = 0; i < spells.size(); i++) {
			JsonObject entry = spells.get(i).getAsJsonObject();
			String id = entry.has("id") ? entry.get("id").getAsString() : "";
			//Resuelto aqui y no al pintar: la etiqueta de la lista se arma concatenando (nivel, escuela,
			//"sin preparar") y el buscador de ListPickerScreen filtra por esa cadena ya montada, asi que el
			//nombre tiene que entrar traducido. Esto corre en el CLIENTE, asi que sale en su idioma.
			String name = net.hawthorn.dndsheets.ContentNames.plain(
				entry.has("name") ? entry.get("name").getAsString() : id);
			int level = entry.has("level") ? entry.get("level").getAsInt() : 0;
			boolean prepared = SpellRegistry.isPrepared(sheet, id);
			//Mismo criterio que ya usa updateButtons() para el botón de lanzar: un truco no tiene "nivel 0",
			//tiene la etiqueta que 5e le da. Antes la lista entera decía "(nv. 0)" para cada truco, que no
			//significa nada en la mesa y no distinguía un truco de un hechizo real con solo mirar la lista.
			StringBuilder label = new StringBuilder(name);
			label.append(level == 0
				? Component.translatable("gui.dndsheets.grimoire.tag_cantrip").getString()
				: Component.translatable("gui.dndsheets.grimoire.tag_level", level).getString());
			//Escuela y "sin preparar" van EN la etiqueta porque el buscador de ListPickerScreen filtra por
			//ella: escribir "evoc" o "sin prep" filtra sin gastar una fila de pantalla en un botón de filtro.
			if (entry.has("school")) label.append(" · ").append(entry.get("school").getAsString());
			if (!prepared) label.append(Component.translatable("gui.dndsheets.grimoire.tag_unprepared").getString());
			result.add(new KnownSpell(id, name, label.toString(), level, prepared));
		}
		result.sort(java.util.Comparator.comparingInt(KnownSpell::level).thenComparing(KnownSpell::name));
		return result;
	}
}
