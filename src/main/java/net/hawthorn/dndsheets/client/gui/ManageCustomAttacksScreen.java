package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.components.ButtonListWidget;
import net.hawthorn.dndsheets.network.ClearCustomAttacksMessage;
import net.hawthorn.dndsheets.network.RemoveCustomAttackMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Lista los ataques personalizados de UN monstruo ya invocado (ver
 * {@link net.hawthorn.dndsheets.MonsterRegistry#customAttacksOf}); pulsar uno lo quita al instante. Los
 * predefinidos del bloque de estadísticas de su especie no aparecen aquí — esos se editan por JSON.</p>
 */
public class ManageCustomAttacksScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int SPACING = 4;

	private final int entityId;
	private final List<String> customAttackNames;

	private ManageCustomAttacksScreen(int entityId, List<String> customAttackNames) {
		super(Component.literal("Ataques personalizados"));
		this.entityId = entityId;
		this.customAttackNames = customAttackNames;
	}

	public static void open(int entityId, List<String> customAttackNames) {
		Minecraft.getInstance().setScreen(new ManageCustomAttacksScreen(entityId, customAttackNames));
	}

	@Override
	protected void init() {
		//Lista con scroll: con muchos ataques personalizados, centrar a mano sin tope empujaba botones
		//fuera de pantalla sin forma de alcanzarlos (ver AUDIT_UX.md).
		ButtonListWidget list = new ButtonListWidget((this.width - BUTTON_WIDTH) / 2, 30, BUTTON_WIDTH, this.height - 44, BUTTON_HEIGHT + SPACING);

		for (String name : customAttackNames) {
			Button button = Button.builder(Component.literal("Quitar: " + name), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new RemoveCustomAttackMessage(entityId, name));
				this.onClose();
			}).bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT).build();
			this.addWidget(button);
			list.addRow(button);
		}

		Button clearAll = Button.builder(Component.literal("Borrar todos"), b -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new ClearCustomAttacksMessage(entityId));
			this.onClose();
		}).bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT).build();
		this.addWidget(clearAll);
		list.addRow(clearAll);

		this.addRenderableWidget(list);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		if (customAttackNames.isEmpty()) {
			guiGraphics.drawCenteredString(this.font, Component.literal("Este monstruo no tiene ataques personalizados."), this.width / 2, 16, 0x888888);
		}
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
