package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.client.gui.components.DirectionalCycleButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>El <b>panel sobre pergamino</b> y su aritmética: el marco, el título, el filete, el tope que impide
 * que se salga por arriba, las filas que se van apilando y los {@code EditBox} con su etiqueta encima.
 * Nada más. No decide qué botones lleva abajo ni cuándo se envía nada.</p>
 *
 * <p>Existe porque había dos pantallas escribiendo esto mismo por separado. {@link SmallFormScreen} es un
 * formulario: se rellena y se pulsa <i>Confirmar</i> una vez. {@link SheetAdjustScreen} es un panel de
 * control: diez acciones sueltas que se aplican cada una por su cuenta, sin ningún <i>Confirmar</i>. Son
 * dos contratos distintos y por eso la segunda nunca pudo heredar de la primera — pero el marco que
 * dibujan es el mismo, y esa parte sí se duplicaba: {@code parseIntOr}, {@code cycleLabel}, el
 * {@code onClose} que vuelve al padre, el {@code tick} de los campos, el {@code Math.max(44, ...)} y el
 * padding de 14px del panel estaban escritos dos veces.</p>
 *
 * <p>El detalle que lo delataba: {@code SmallFormScreen} llevaba el comentario <i>"Mismo tope que
 * SheetAdjustScreen"</i>. La base había copiado el número de la pantalla que iba por libre, no al revés.
 * Con esto el número vive en un sitio.</p>
 */
public abstract class FormPanelScreen extends Screen {

	protected static final int FIELD_WIDTH = 160;
	protected static final int FIELD_HEIGHT = 20;
	//30, no 26: deja 10px libres arriba de cada campo para su etiqueta (ver addField) sin pisar el
	//campo anterior — antes ningún campo de este formulario mostraba en pantalla para qué era, solo su
	//Component de narración (invisible, solo lectores de accesibilidad) y el valor por defecto ya escrito.
	protected static final int ROW_HEIGHT = 30;
	//Alto de la banda de cabecera por encima de la primera fila: tiene que dejar sitio al título (8 px), al
	//filete y a la etiqueta del primer campo, que se dibuja en formTop-10.
	protected static final int TITLE_BAND = 34;

	private final int titleRows;
	private final Screen parent;
	private final List<EditBox> editBoxes = new ArrayList<>();
	private final List<String> editBoxLabels = new ArrayList<>();
	protected int centerX;
	protected int formTop;
	private int cursorY;
	protected int formBottom;

	protected FormPanelScreen(Component title, int titleRows, Screen parent) {
		super(title);
		this.titleRows = titleRows;
		this.parent = parent;
	}

	/** Añade las filas del panel, en orden, con addField(...)/addFieldRow(...)/addCycleButton(...). */
	protected abstract void buildForm();

	/**
	 * <p>Ancho de una fila. Por defecto {@link #FIELD_WIDTH}, que es lo que han usado siempre las pantallas
	 * que heredan de {@link SmallFormScreen}: mientras nadie lo sobrescriba, la aritmética es idéntica.</p>
	 */
	protected int formWidth() {
		return FIELD_WIDTH;
	}

	/** Separación vertical entre filas. Un panel apretado puede bajarla para que le quepan más. */
	protected int rowHeight() {
		return ROW_HEIGHT;
	}

	/** Alto de la banda de cabecera. Un panel que pinte una segunda línea bajo el título necesita más. */
	protected int titleBand() {
		return TITLE_BAND;
	}

	/** Alto por fila para el centrado inicial: cuántas filas caben por encima del centro. */
	protected final void layoutTop() {
		centerX = this.width / 2;
		//Sin este tope, un panel con muchas filas centrado en height/2 empujaba las primeras (y su título)
		//fuera de la pantalla en ventanas bajas o con GUI Scale alto, en vez de solo quedar apretado: no se
		//veía ni se podía pulsar lo de arriba.
		formTop = Math.max(44, this.height / 2 - rowHeight() * titleRows);
		cursorY = formTop;
		editBoxes.clear();
		editBoxLabels.clear();
	}

	protected final int nextRowY() {
		int y = cursorY;
		cursorY += rowHeight();
		return y;
	}

	protected EditBox addField(String label, String defaultValue, int maxLength) {
		int y = nextRowY();
		return registerBox(label, defaultValue, maxLength, centerX - formWidth() / 2, y, formWidth());
	}

	/**
	 * <p>Con posición explícita, para una fila que mezcla un campo con botones que no son de este panel
	 * (aplicar, fijar...). Sigue quedando registrado, así que hereda igual el {@code tick} del cursor y su
	 * etiqueta encima — que es justo lo que se olvidaba al colocarlos a mano con {@code new EditBox}.</p>
	 */
	protected EditBox addFieldAt(String label, String defaultValue, int maxLength, int y, int x, int width) {
		return registerBox(label, defaultValue, maxLength, x, y, width);
	}

	/**
	 * <p>Varios campos en UNA fila, repartidos a lo ancho de {@link #formWidth()} con 4px de separación.</p>
	 *
	 * <p>El reparto se calcula, no se escribe a mano: {@code SheetAdjustScreen} tenía dos campos de 90px
	 * fijos dentro de 190px, y cuando quiso meter un botón en esa misma fila le quedaron 190-188 = <b>2
	 * píxeles</b> de ancho, prácticamente imposible de pulsar. Ese es uno de los dos peores bugs de layout
	 * del proyecto, y sale justo de repartir a ojo.</p>
	 */
	protected EditBox[] addFieldRow(String[] labels, String[] defaults, int maxLength) {
		int y = nextRowY();
		int columns = labels.length;
		int gap = 4;
		int width = (formWidth() - gap * (columns - 1)) / columns;
		int left = centerX - formWidth() / 2;

		EditBox[] boxes = new EditBox[columns];
		for (int i = 0; i < columns; i++) {
			boxes[i] = registerBox(labels[i], defaults[i], maxLength, left + i * (width + gap), y, width);
		}
		return boxes;
	}

	private EditBox registerBox(String label, String defaultValue, int maxLength, int x, int y, int width) {
		EditBox box = new EditBox(this.font, x, y, width, FIELD_HEIGHT, Component.literal(label));
		box.setValue(defaultValue);
		box.setMaxLength(maxLength);
		this.addWidget(box);
		if (editBoxes.isEmpty()) this.setInitialFocus(box);
		editBoxes.add(box);
		editBoxLabels.add(label);
		return box;
	}

	protected CycleField addCycleButton(String prefix, String[] options) {
		return addCycleButton(prefix, options, options, 0);
	}

	//Para opciones cuyo valor real (guardado/enviado al servidor) es un código interno poco claro para un
	//DM (p.ej. "str"/"dex") — displayLabels es SOLO lo que se muestra en el botón, en el mismo orden que
	//options; CycleField.value() sigue devolviendo el código interno de options, no el texto mostrado.
	protected CycleField addCycleButton(String prefix, String[] options, String[] displayLabels) {
		return addCycleButton(prefix, options, displayLabels, 0);
	}

	//Con índice inicial: para prellenar un formulario de EDICIÓN (ver ContentFormScreen) con el valor que ya
	//tenía la entrada, en vez de arrancar siempre en options[0] como si fuera nueva.
	protected CycleField addCycleButton(String prefix, String[] options, String[] displayLabels, int initialIndex) {
		return addCycleButton(prefix, options, displayLabels, initialIndex, nextRowY(), centerX - formWidth() / 2, formWidth());
	}

	/** Con posición explícita: para un panel que comparte fila entre un cíclico y su botón de aplicar. */
	protected CycleField addCycleButton(String prefix, String[] options, String[] displayLabels, int initialIndex,
			int y, int x, int width) {
		CycleField field = new CycleField(options);
		field.index = initialIndex >= 0 && initialIndex < options.length ? initialIndex : 0;
		field.button = this.addRenderableWidget(new DirectionalCycleButton(x, y, width, FIELD_HEIGHT,
			cycleLabel(prefix, displayLabels[field.index]),
			() -> {
				field.index = (field.index + 1) % options.length;
				field.button.setMessage(cycleLabel(prefix, displayLabels[field.index]));
			},
			() -> {
				field.index = (field.index - 1 + options.length) % options.length;
				field.button.setMessage(cycleLabel(prefix, displayLabels[field.index]));
			}));
		return field;
	}

	protected static int parseIntOr(String value, int fallback) {
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	protected static Component cycleLabel(String prefix, String value) {
		return Component.literal(prefix + ": " + value);
	}

	/** Botón cíclico con su índice actual — ver addCycleButton. */
	protected static final class CycleField {
		private final String[] options;
		private Button button;
		private int index = 0;

		private CycleField(String[] options) {
			this.options = options;
		}

		public String value() {
			return options[index];
		}

		public int index() {
			return index;
		}
	}

	/**
	 * <p>Marco, título y filete. Se llama desde el {@code render} de cada subclase, que decide qué más
	 * pinta encima — un panel de control puede querer una segunda línea de solo lectura bajo el título.</p>
	 */
	protected final void renderPanelChrome(GuiGraphics guiGraphics) {
		//La cabecera necesita su propia banda. El título estaba en formTop-16 (ocupa hasta formTop-8) y la
		//etiqueta del primer campo arranca en formTop-10: se pisaban dos filas de píxeles, y con la fuente
		//de Minecraft eso no se lee como "juntos", se lee como texto duplicado y emborronado.
		GuiStyle.panel(guiGraphics, centerX - formWidth() / 2 - 14, formTop - titleBand(), centerX + formWidth() / 2 + 14, formBottom);
		guiGraphics.drawCenteredString(this.font, title, this.width / 2, formTop - titleBand() + 6, GuiStyle.TITLE_COLOR);
		//Filete de separación, el mismo recurso que usa ListPickerScreen para su cabecera.
		GuiStyle.rule(guiGraphics, centerX - formWidth() / 2 - 6, centerX + formWidth() / 2 + 6, formTop - 18);
	}

	/** Los campos con su etiqueta encima. Va después de super.render para quedar por delante del panel. */
	protected final void renderFields(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		for (int i = 0; i < editBoxes.size(); i++) {
			EditBox box = editBoxes.get(i);
			guiGraphics.drawString(this.font, editBoxLabels.get(i), box.getX(), box.getY() - 10, GuiStyle.TITLE_COLOR, false);
			box.render(guiGraphics, mouseX, mouseY, partialTicks);
		}
	}

	//Vuelve a la pantalla anterior en vez de cerrar todo el menú — mismo mecanismo de navegación que
	//ListPickerScreen, ver esa clase.
	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public final void tick() {
		for (EditBox box : editBoxes) box.tick();
	}

	@Override
	public final boolean isPauseScreen() {
		return false;
	}
}
