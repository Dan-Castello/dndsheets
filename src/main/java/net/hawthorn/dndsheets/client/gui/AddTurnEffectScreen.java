package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.TurnEffectApplyMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Formulario para aplicar un efecto de estado (veneno, etc.) a UN jugador ya elegido en
 * {@link PlayerPickerScreen}, abierto desde el botón "Aplicar efecto" de {@link TurnControlScreen}
 * (equivalente en GUI a {@code /dndturns effect}). El dado se elige con un
 * botón cíclico sobre las mismas sugerencias que ya usa {@code TurnCommand}, en vez de texto libre.</p>
 */
public class AddTurnEffectScreen extends SmallFormScreen {
	private static final String[] DICE_OPTIONS = {"1d4", "1d6", "1d8", "1d10", "1d12", "2d6", "2d8"};

	private final String targetUuid;
	private EditBox nameBox;
	private EditBox turnsBox;
	private CycleField dice;

	private AddTurnEffectScreen(String targetUuid, Screen parent) {
		super(Component.translatable("gui.dndsheets.add_effect.title"), 2, parent);
		this.targetUuid = targetUuid;
	}

	public static void open(String targetUuid) {
		Minecraft.getInstance().setScreen(new AddTurnEffectScreen(targetUuid, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		nameBox = addField("Nombre", "veneno", 40);
		dice = addCycleButton("Dado", DICE_OPTIONS);
		turnsBox = addField("Turnos", "3", 2);
	}

	@Override
	protected void onConfirm() {
		String name = nameBox.getValue().isBlank() ? "veneno" : nameBox.getValue();
		int turns = parseIntOr(turnsBox.getValue(), 3);
		DndsheetsMod.PACKET_HANDLER.sendToServer(new TurnEffectApplyMessage(targetUuid, name, dice.value(), turns));
	}
}
