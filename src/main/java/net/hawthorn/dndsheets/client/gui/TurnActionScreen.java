package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.TurnActionManager;
import net.hawthorn.dndsheets.network.TurnActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * <p>Las tres acciones de turno que no son atacar ni lanzar un conjuro, para quien usa el ítem de Acciones
 * de Turno. Cada botón dice lo que HACE la acción y no solo cómo se llama: "Esquivar" a secas no le dice
 * nada a quien nunca jugó a D&amp;D, y este mod se juega sobre todo con gente que no lo ha jugado.</p>
 */
public class TurnActionScreen extends ModalDialogScreen {
	private static final int WIDTH = 260;
	private static final int HEIGHT = 104;

	protected TurnActionScreen() {
		super(Component.literal("Acciones de Turno"), WIDTH, HEIGHT);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new TurnActionScreen());
	}

	@Override
	protected void init() {
		addAction(30, TurnActionManager.TurnAction.DODGE, "Esquivar — te atacan con desventaja");
		addAction(54, TurnActionManager.TurnAction.DASH, "Correr — el doble de movimiento");
		addAction(78, TurnActionManager.TurnAction.DISENGAGE, "Desengancharse — alejarte no provoca ataques");
	}

	private void addAction(int y, TurnActionManager.TurnAction action, String label) {
		addModalButton(20, y, WIDTH - 40, 20, Component.literal(label), button -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new TurnActionMessage(action));
			this.onClose();
		});
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderPanel(guiGraphics);
		guiGraphics.drawCenteredString(this.font, Component.literal("Gasta tu acción de este turno en:"), this.width / 2, dialogTop() + 8, 0xFFFFFF);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
