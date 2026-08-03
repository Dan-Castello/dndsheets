package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.components.ButtonListWidget;
import net.hawthorn.dndsheets.network.PresetApplyMessage;
import net.hawthorn.dndsheets.network.PresetApplyToMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Selector de presets de clase: la lista (ids + nombres) la manda el servidor en
 * {@code PresetListMessage} (pedida por {@code PresetListRequestMessage} al pulsar "Presets" en la
 * hoja, o por un DM tras elegir a otro jugador en {@link PlayerPickerScreen} — ver AUDIT_UX.md, DM #2),
 * porque el registro de presets solo vive en memoria del servidor. Elegir uno rellena la clase, el dado
 * de golpe y las características de la hoja — hay que cerrarla y reabrirla para verlo. {@code
 * targetUuid} vacío significa "aplícalo a mi propia hoja".</p>
 */
public class PresetScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int SPACING = 4;

	private final String targetUuid;
	private final List<String> ids;
	private final List<String> names;
	private ButtonListWidget list;

	private PresetScreen(String targetUuid, List<String> ids, List<String> names) {
		super(Component.literal("Presets de clase"));
		this.targetUuid = targetUuid;
		this.ids = ids;
		this.names = names;
	}

	public static void open(String targetUuid, List<String> ids, List<String> names) {
		Minecraft.getInstance().setScreen(new PresetScreen(targetUuid, ids, names));
	}

	@Override
	protected void init() {
		//Lista con scroll: con muchos presets cargados, centrar a mano sin tope empujaba botones fuera de
		//pantalla sin forma de alcanzarlos (ver AUDIT_UX.md).
		list = new ButtonListWidget((this.width - BUTTON_WIDTH) / 2, 30, BUTTON_WIDTH, this.height - 44, BUTTON_HEIGHT + SPACING);
		for (int i = 0; i < names.size(); i++) {
			String presetId = ids.get(i);
			Button button = Button.builder(Component.literal(names.get(i)), b -> {
				if (targetUuid.isEmpty()) {
					DndsheetsMod.PACKET_HANDLER.sendToServer(new PresetApplyMessage(presetId));
				} else {
					DndsheetsMod.PACKET_HANDLER.sendToServer(new PresetApplyToMessage(targetUuid, presetId));
				}
				this.onClose();
			}).bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT).build();
			this.addWidget(button);
			list.addRow(button);
		}
		this.addRenderableWidget(list);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	//El botón bajo el cursor se queda con el scroll por defecto (Screen le entrega el evento a lo que
	//esté justo debajo del mouse, y un botón de fila no hace nada con él) — de ahí que antes solo se
	//pudiera desplazar pasando el mouse por huecos sin botón. Forzarlo siempre a la lista arregla eso.
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		return list.mouseScrolled(mouseX, mouseY, delta) || super.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		guiGraphics.drawCenteredString(this.font, Component.literal("Elige un preset de clase"), this.width / 2, 16, 0xFFFFFF);

		if (names.isEmpty()) {
			guiGraphics.drawCenteredString(this.font, Component.literal("No hay presets cargados (pide al DM /dndpresets load)."), this.width / 2, this.height / 2, 0x888888);
		}

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
