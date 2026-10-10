package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.TurnActionManager;
import net.hawthorn.dndsheets.network.TurnActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * <p>The three turn actions that aren't attacking or casting a spell, for whoever uses the Turn
 * Actions item. Each button states what the action DOES, not just its name: "Dodge" alone tells
 * someone who's never played D&amp;D nothing, and this mod is played mostly by people who haven't.</p>
 */
public class TurnActionScreen extends ModalDialogScreen {
	private static final int WIDTH = 340;
	private static final int HEIGHT = 204;

	protected TurnActionScreen() {
		super(Component.translatable("gui.dndsheets.turn_action.title"), WIDTH, HEIGHT);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new TurnActionScreen());
	}

	//Two pages so the dialog fits small screens: the actions everyone has, and the class features.
	private boolean classPage = false;

	@Override
	protected void init() {
		TurnActionManager.TurnAction[] actions = classPage
			? new TurnActionManager.TurnAction[]{TurnActionManager.TurnAction.ACTION_SURGE, TurnActionManager.TurnAction.FLURRY_OF_BLOWS,
				TurnActionManager.TurnAction.PATIENT_DEFENSE, TurnActionManager.TurnAction.STEP_DASH, TurnActionManager.TurnAction.STEP_DISENGAGE}
			: new TurnActionManager.TurnAction[]{TurnActionManager.TurnAction.DODGE, TurnActionManager.TurnAction.DASH,
				TurnActionManager.TurnAction.DISENGAGE, TurnActionManager.TurnAction.SEARCH, TurnActionManager.TurnAction.HIDE,
				TurnActionManager.TurnAction.READY};
		String[] keys = classPage
			? new String[]{"surge", "flurry", "patient_defense", "step_dash", "step_disengage"}
			: new String[]{"dodge", "dash", "disengage", "search", "hide", "ready"};
		for (int i = 0; i < actions.length; i++) addAction(30 + i * 24, actions[i], "gui.dndsheets.turn_action." + keys[i]);

		addModalButton(20, 30 + 6 * 24, WIDTH - 40, 20,
			Component.translatable(classPage ? "gui.dndsheets.turn_action.page_basic" : "gui.dndsheets.turn_action.page_class"), button -> {
				classPage = !classPage;
				this.rebuildWidgets();
			});
	}

	private void addAction(int y, TurnActionManager.TurnAction action, String labelKey) {
		addModalButton(20, y, WIDTH - 40, 20, Component.translatable(labelKey), button -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new TurnActionMessage(action));
			this.onClose();
		});
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderPanel(guiGraphics);
		guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.turn_action.prompt"), this.width / 2, dialogTop() + 8, 0xFFFFFF);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
