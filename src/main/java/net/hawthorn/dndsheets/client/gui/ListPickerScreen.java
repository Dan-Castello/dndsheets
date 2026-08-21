package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.client.gui.components.ButtonListWidget;
import net.hawthorn.dndsheets.client.gui.components.TomeButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <p>Base compartida para pantallas de "lista vertical de botones": título centrado + panel con
 * borde detrás de una {@link ButtonListWidget} con scroll automático si no caben todos los botones.
 * Cubre tanto los selectores del Panel de DM (jugador, preset, rasgo, opción de personaje, ataque a
 * quitar, acción de monstruo) como los menús de botón fijo (Panel de DM, Modo turnos) — un menú fijo
 * no es más que una lista que nunca necesita desplazarse. Antes cada una repetía las mismas
 * constantes de layout, el reenvío de {@code mouseScrolled} a la lista y {@code isPauseScreen() ->
 * false}.</p>
 *
 * <p>{@code init()} e {@code render()} no son {@code final}: una pantalla con contenido extra (un
 * subtítulo, un botón fijo bajo la lista) puede sobrescribirlos, llamar a {@code super} primero y
 * añadir lo suyo encima — más simple que intentar prever cada variante con parámetros.</p>
 *
 * <p><b>Navegación:</b> si esta pantalla se abrió desde otra (Panel de DM, selector de jugador...),
 * pasa esa pantalla como {@code parent}. Elegir una fila, pulsar "&lt; Atrás" o Escape vuelve a
 * {@code parent} en vez de cerrar todo el menú — antes solo {@code CharacterOptionListScreen} hacía
 * esto a mano; ahora es el comportamiento por defecto de la base. Sin {@code parent} (pantallas raíz
 * como {@code DmPanelScreen}/{@code MonsterActionScreen}) se comporta como antes: cierra el menú.</p>
 */
public abstract class ListPickerScreen extends Screen {
	protected static final int BUTTON_HEIGHT = 20;
	protected static final int SPACING = 4;
	private static final int LIST_TOP = 30;
	private static final int PANEL_PADDING = 10;
	private static final int BACK_BUTTON_WIDTH = 50;
	private static final int BACK_BUTTON_HEIGHT = 14;
	//"< Atrás" y el título comparten la fila de cabecera, así que sus coordenadas tienen que salir de las
	//mismas constantes. Antes el botón se colocaba en init() y el título en render() por su cuenta, y se
	//pisaban: el botón caía en y=8..22 y el título en y=16..24, además de solaparse en horizontal en
	//cuanto el título era largo ("Elige un preset de clase"). Se leía como texto duplicado.
	private static final int BACK_BUTTON_TOP = 10;
	/** Centro vertical del botón, para que el rótulo del título quede a su misma altura. */
	private static final int TITLE_Y = BACK_BUTTON_TOP + (BACK_BUTTON_HEIGHT - 8) / 2;
	private static final int SEARCH_HEIGHT = 16;
	private static final int SEARCH_GAP = 6;

	private final Screen parent;
	private ButtonListWidget list;
	private EditBox searchBox;
	//Todas las filas creadas por buildRows(), no solo las visibles — con buscador, list.replaceRows(...)
	//solo recibe el subconjunto que coincide con el texto tipeado (ver applyFilter()).
	private final List<Button> allButtons = new ArrayList<>();
	private final List<String> allLabels = new ArrayList<>();

	/** Pantalla raíz, sin nada a lo que volver (p. ej. abierta por keybind o clic derecho). */
	protected ListPickerScreen(Component title) {
		this(title, null);
	}

	/** {@code parent} es la pantalla a la que "&lt; Atrás"/Escape deben volver; null si es la pantalla raíz de su flujo. */
	protected ListPickerScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	/** Ancho de los botones de la lista y del panel. Sobrescribir para una lista más ancha (p. ej. Grimorio). */
	protected int buttonWidth() {
		return 200;
	}

	/** true agrega una caja de búsqueda arriba de la lista que filtra las filas por texto — ver {@link #addRow}. */
	protected boolean searchable() {
		return false;
	}

	/** Y donde empieza la lista, bajo el título (y la búsqueda, si {@link #searchable()}). Sobrescribir para dejar hueco a un subtítulo. */
	/** Borde izquierdo del panel. Lo usan init() (para colocar "< Atrás") y render(), que deben coincidir. */
	private int panelLeft() {
		return (this.width - buttonWidth()) / 2 - PANEL_PADDING;
	}

	protected int listTop() {
		return searchable() ? LIST_TOP + SEARCH_HEIGHT + SEARCH_GAP : LIST_TOP;
	}

	/** Alto disponible para la lista. Sobrescribir para dejar hueco a un botón fijo debajo. */
	protected int listHeight() {
		return this.height - listTop() - 14;
	}

	/** Añade las filas de la lista, en orden, con {@link #addRow}. Llamado desde {@code init()}. */
	protected abstract void buildRows();

	/** Texto a mostrar centrado en pantalla si la lista queda vacía. Null = no mostrar nada. */
	protected Component emptyMessage() {
		return null;
	}

	protected final Button addRow(Component label, Button.OnPress onPress) {
		//TomeButton y no Button: el botón gris de piedra de vanilla sobre un panel de cuero se lee como un
		//widget prestado de otra interfaz. Al cambiarlo aquí se repintan por dentro las más de cuarenta
		//pantallas que cuelgan de esta base, igual que GuiStyle hizo con sus marcos.
		Button button = TomeButton.of(label, onPress, 0, 0, buttonWidth(), BUTTON_HEIGHT);
		this.addWidget(button);
		//Sin buscador: se agrega directo, como siempre. Con buscador, applyFilter() decide qué entra a
		//"list" después de que buildRows() termine de llamar a addRow() para todas las filas.
		if (searchable()) {
			allButtons.add(button);
			allLabels.add(label.getString().toLowerCase(Locale.ROOT));
		} else {
			list.addRow(button);
		}
		return button;
	}

	@Override
	protected void init() {
		allButtons.clear();
		allLabels.clear();
		list = new ButtonListWidget((this.width - buttonWidth()) / 2, listTop(), buttonWidth(), listHeight(), BUTTON_HEIGHT + SPACING);

		if (searchable()) {
			searchBox = new EditBox(this.font, (this.width - buttonWidth()) / 2, LIST_TOP, buttonWidth(), SEARCH_HEIGHT, Component.translatable("gui.dndsheets.common.search"));
			searchBox.setHint(Component.translatable("gui.dndsheets.common.search_hint"));
			searchBox.setResponder(text -> applyFilter());
			this.addRenderableWidget(searchBox);
			this.setInitialFocus(searchBox);
		} else {
			searchBox = null;
		}

		buildRows();
		applyFilter();
		this.addRenderableWidget(list);

		if (parent != null) {
			this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.common.back"), b -> this.onClose(),
				panelLeft() + 4, BACK_BUTTON_TOP, BACK_BUTTON_WIDTH, BACK_BUTTON_HEIGHT));
		}
	}

	//No-op sin buscador (allButtons se queda vacío, cada addRow ya fue directo a "list"). Con buscador,
	//reconstruye la lista visible cada vez que cambia el texto — O(n²) por el contains() de replaceRows,
	//aceptable para listas de contenido cargado (decenas de filas, no miles).
	private void applyFilter() {
		if (!searchable()) return;
		String query = searchBox.getValue().trim().toLowerCase(Locale.ROOT);
		List<Button> visible = new ArrayList<>();
		for (int i = 0; i < allButtons.size(); i++) {
			if (query.isEmpty() || allLabels.get(i).contains(query)) visible.add(allButtons.get(i));
		}
		list.replaceRows(visible);
	}

	@Override
	public void tick() {
		if (searchBox != null) searchBox.tick();
	}

	//Volver a la pantalla anterior en vez de cerrar el menú entero — setScreen(null) cuando no hay
	//parent replica exactamente el comportamiento por defecto de Screen#onClose().
	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		return list.mouseScrolled(mouseX, mouseY, delta) || super.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);

		int left = panelLeft();
		int right = this.width - left;
		//La cabecera está en un sitio fijo (más abajo), así que el borde superior del panel también, en vez
		//de depender de listTop() — una pantalla con subtítulo (Grimorio) mueve listTop() hacia abajo sin
		//dejar el título sobresaliendo por encima del panel.
		int top = 16 - PANEL_PADDING;
		int bottom = listTop() + listHeight() + PANEL_PADDING;
		GuiStyle.panel(guiGraphics, left, top, right, bottom);

		//Con "< Atrás" el título se centra en el hueco QUE QUEDA a su derecha, no en la pantalla entera:
		//centrado en la pantalla, un título largo se metía por debajo del botón.
		int titleLeft = parent != null ? left + 4 + BACK_BUTTON_WIDTH : left;
		guiGraphics.drawCenteredString(this.font, this.title, (titleLeft + right) / 2, TITLE_Y, GuiStyle.TITLE_COLOR);
		//Filete bajo el título: separa la cabecera del contenido sin gastar una fila entera de alto, que es
		//lo que costaría un separador de verdad en una lista con scroll.
		GuiStyle.rule(guiGraphics, left + 8, right - 8, 28);

		Component empty = emptyMessage();
		if (empty != null) {
			guiGraphics.drawCenteredString(this.font, empty, this.width / 2, this.height / 2, GuiStyle.MUTED_COLOR);
		}

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
