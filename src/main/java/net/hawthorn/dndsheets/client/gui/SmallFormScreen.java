package net.hawthorn.dndsheets.client.gui;

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
public abstract class SmallFormScreen extends Screen {
	protected static final int FIELD_WIDTH = 160;
	protected static final int FIELD_HEIGHT = 20;
	protected static final int ROW_HEIGHT = 26;

	private final int titleRows;
	private final List<EditBox> editBoxes = new ArrayList<>();
	protected int centerX;
	protected int formTop;
	private int cursorY;
	private int formBottom;

	protected SmallFormScreen(Component title, int titleRows) {
		super(title);
		this.titleRows = titleRows;
	}

	/** Añade los campos/botones del formulario, en orden, con addField(...)/addCycleButton(...). */
	protected abstract void buildForm();

	/** Se llama al pulsar "Confirmar", antes de cerrar la pantalla. */
	protected abstract void onConfirm();

	@Override
	protected final void init() {
		centerX = this.width / 2;
		formTop = this.height / 2 - ROW_HEIGHT * titleRows;
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
		return box;
	}

	protected CycleField addCycleButton(String prefix, String[] options) {
		int y = nextRowY();
		CycleField field = new CycleField(options);
		field.button = this.addRenderableWidget(Button.builder(cycleLabel(prefix, field.value()), button -> {
			field.index = (field.index + 1) % options.length;
			field.button.setMessage(cycleLabel(prefix, field.value()));
		}).bounds(centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT).build());
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
		for (EditBox box : editBoxes) box.render(guiGraphics, mouseX, mouseY, partialTicks);
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
