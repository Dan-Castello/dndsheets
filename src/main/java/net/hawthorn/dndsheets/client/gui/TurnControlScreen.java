package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.TurnControlMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Turn mode control from the DM Panel (GUI equivalent of {@code /dndturns
 * start|next|cancel|end}). "Start" always uses the default radius ({@link
 * net.hawthorn.dndsheets.command.TurnCommand#DEFAULT_RADIUS}) — a different radius still requires
 * the command.</p>
 */
public class TurnControlScreen extends ListPickerScreen {
	private static final String[] ACTIONS = {"start", "next", "cancel", "end"};
	private static final String[] LABELS = {"gui.dndsheets.turn_control.start", "gui.dndsheets.turn_control.next", "gui.dndsheets.turn_control.cancel", "gui.dndsheets.turn_control.end"};

	private TurnControlScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.dm_panel.turn_mode"), parent);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new TurnControlScreen(Minecraft.getInstance().screen));
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < ACTIONS.length; i++) {
			String action = ACTIONS[i];
			addRow(Component.translatable(LABELS[i]), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new TurnControlMessage(action));
				this.onClose();
			});
		}

		//Applying a status effect (poison, etc.) previously only existed as a hand-typed /dndturns effect,
		//with no GUI to pick the die or duration.
		addRow(Component.translatable("gui.dndsheets.add_effect.title"), b ->
			PlayerPickerScreen.open(Component.translatable("gui.dndsheets.turn_control.pick_effect_target"), AddTurnEffectScreen::open));
	}
}
