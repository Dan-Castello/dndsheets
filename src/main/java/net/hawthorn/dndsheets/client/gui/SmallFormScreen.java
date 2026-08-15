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

//Base para formularios cortos de una sola columna (nombre, un par de campos, botones
//Confirmar/Cancelar) abiertos desde el Panel de DM o la Vara de DM: SpawnGenericScreen,
//AddTurnEffectScreen y AddMonsterAttackScreen duplicaban las mismas constantes de layout, el helper
//cycleLabel/parseIntOr y el patrón de render/tick de sus EditBox — ver AUDIT_REPORT_2026.md F6. Mismo
//espíritu que ModalDialogScreen, pero para un formulario vertical centrado en vez de una caja de diálogo
//de tamaño fijo.
//
//"Cancelar" (y "Confirmar", una vez enviado el mensaje) vuelven a `parent` en vez de cerrar todo el
//menú — mismo mecanismo de navegación que ListPickerScreen, ver esa clase.
public abstract class SmallFormScreen extends Screen {
	protected static final int FIELD_WIDTH = 160;
	protected static final int FIELD_HEIGHT = 20;
	//30, no 26: deja 10px libres arriba de cada campo para su etiqueta (ver addField) sin pisar el
	//campo anterior — antes ningún campo de este formulario mostraba en pantalla para qué era, solo su
	//Component de narración (invisible, solo lectores de accesibilidad) y el valor por defecto ya escrito.
	protected static final int ROW_HEIGHT = 30;

	private final int titleRows;
	private final Screen parent;
	private final List<EditBox> editBoxes = new ArrayList<>();
	private final List<String> editBoxLabels = new ArrayList<>();
	protected int centerX;
	protected int formTop;
	private int cursorY;
	private int formBottom;

	protected SmallFormScreen(Component title, int titleRows, Screen parent) {
		super(title);
		this.titleRows = titleRows;
		this.parent = parent;
	}

	/** Añade los campos/botones del formulario, en orden, con addField(...)/addCycleButton(...). */
	protected abstract void buildForm();

	/** Se llama al pulsar "Confirmar", antes de cerrar la pantalla. */
	protected abstract void onConfirm();

	/** true agrega una fila "Borrar" propia bajo Confirmar/Cancelar — ver {@link #onDelete()}. */
	protected boolean showDeleteButton() {
		return false;
	}

	/** Solo se llama si {@link #showDeleteButton()} es true; cierra la pantalla después, igual que onConfirm. */
	protected void onDelete() {
	}

	protected Component deleteButtonLabel() {
		return Component.literal("Borrar");
	}

	@Override
	protected final void init() {
		centerX = this.width / 2;
		//Mismo tope que SheetAdjustScreen: sin esto, un formulario con muchos campos (los del creador de
		//contenido, p.ej.) centrado en height/2 sin piso empujaba las primeras filas (y su título) fuera de
		//la pantalla en ventanas bajas/GUI Scale alto, en vez de solo quedar apretado.
		formTop = Math.max(44, this.height / 2 - ROW_HEIGHT * titleRows);
		cursorY = formTop;
		editBoxes.clear();
		buildForm();

		int y = nextRowY();
		this.addRenderableWidget(Button.builder(Component.literal("Confirmar"), button -> {
			onConfirm();
			this.onClose();
		}).bounds(centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH / 2 - 2, FIELD_HEIGHT).build());

		this.addRenderableWidget(Button.builder(Component.literal("Cancelar"), button -> this.onClose())
			.bounds(centerX + 2, y, FIELD_WIDTH / 2 - 2, FIELD_HEIGHT).build());

		formBottom = y + FIELD_HEIGHT + 10;

		//Borrar vive en el detalle de lo que se está editando en vez de una fila aparte en la lista de
		//afuera (ManageCustomAttacksScreen/DungeonPieceListScreen/ContentEntryListScreen usaban antes una
		//fila "Borrar: X" extra por cada elemento — ocupaba el doble de alto que hacía falta).
		if (showDeleteButton()) {
			int deleteY = nextRowY();
			this.addRenderableWidget(Button.builder(deleteButtonLabel(), button -> {
				onDelete();
				this.onClose();
			}).bounds(centerX - FIELD_WIDTH / 2, deleteY, FIELD_WIDTH, FIELD_HEIGHT).build());
			formBottom = deleteY + FIELD_HEIGHT + 10;
		}
	}

	private int nextRowY() {
		int y = cursorY;
		cursorY += ROW_HEIGHT;
		return y;
	}

	protected EditBox addField(String label, String defaultValue, int maxLength) {
		int y = nextRowY();
		EditBox box = new EditBox(this.font, centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal(label));
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
		int y = nextRowY();
		CycleField field = new CycleField(options);
		field.index = initialIndex >= 0 && initialIndex < options.length ? initialIndex : 0;
		field.button = this.addRenderableWidget(new DirectionalCycleButton(centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT,
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

	private static Component cycleLabel(String prefix, String value) {
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
	}

	@Override
	public final void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		GuiStyle.panel(guiGraphics, centerX - FIELD_WIDTH / 2 - 14, formTop - 24, centerX + FIELD_WIDTH / 2 + 14, formBottom);
		guiGraphics.drawCenteredString(this.font, title, this.width / 2, formTop - 16, GuiStyle.TITLE_COLOR);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		for (int i = 0; i < editBoxes.size(); i++) {
			EditBox box = editBoxes.get(i);
			guiGraphics.drawString(this.font, editBoxLabels.get(i), box.getX(), box.getY() - 10, GuiStyle.TITLE_COLOR, false);
			box.render(guiGraphics, mouseX, mouseY, partialTicks);
		}
	}

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
