package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.components.ButtonListWidget;
import net.hawthorn.dndsheets.network.TraitGrantMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Último paso de conceder un rasgo desde el Panel de DM: la lista (ids + nombres) la manda el
 * servidor en {@code TraitListMessage} (pedida por {@code TraitListRequestMessage} tras elegir el
 * jugador objetivo en {@link PlayerPickerScreen}), porque el registro de rasgos solo vive en memoria del
 * servidor — mismo patrón que {@link PresetScreen}.</p>
 */
public class TraitGrantScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int SPACING = 4;

	private final String targetUuid;
	private final List<String> ids;
	private final List<String> names;

	private TraitGrantScreen(String targetUuid, List<String> ids, List<String> names) {
		super(Component.literal("Conceder rasgo"));
		this.targetUuid = targetUuid;
		this.ids = ids;
		this.names = names;
	}

	public static void open(String targetUuid, List<String> ids, List<String> names) {
		Minecraft.getInstance().setScreen(new TraitGrantScreen(targetUuid, ids, names));
	}

	@Override
	protected void init() {
		//Lista con scroll: con muchos rasgos cargados, centrar a mano sin tope empujaba botones fuera de
		//pantalla sin forma de alcanzarlos (ver AUDIT_UX.md).
		ButtonListWidget list = new ButtonListWidget((this.width - BUTTON_WIDTH) / 2, 30, BUTTON_WIDTH, this.height - 44, BUTTON_HEIGHT + SPACING);
		for (int i = 0; i < names.size(); i++) {
			String traitId = ids.get(i);
			Button button = Button.builder(Component.literal(names.get(i)), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new TraitGrantMessage(targetUuid, traitId));
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

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		guiGraphics.drawCenteredString(this.font, Component.literal("Elige un rasgo para conceder"), this.width / 2, 16, 0xFFFFFF);

		if (names.isEmpty()) {
			guiGraphics.drawCenteredString(this.font, Component.literal("No hay rasgos cargados (/dndtraits load)."), this.width / 2, this.height / 2, 0x888888);
		}

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
