package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.TurnEffectApplyMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Form to apply a status effect (poison, etc.) to ONE player already chosen in
 * {@link PlayerPickerScreen}, opened from {@link TurnControlScreen}'s "Apply effect" button
 * (the GUI equivalent of {@code /dndturns effect}). The die is picked with a
 * cycle button over the same suggestions {@code TurnCommand} already uses, instead of free text.</p>
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
		nameBox = addField(net.minecraft.client.resources.language.I18n.get("gui.dndsheets.form.name"), "poison", 40);
		dice = addCycleButton(net.minecraft.client.resources.language.I18n.get("gui.dndsheets.form.dice"), DICE_OPTIONS);
		turnsBox = addField(net.minecraft.client.resources.language.I18n.get("gui.dndsheets.form.turns"), "3", 2);
	}

	@Override
	protected void onConfirm() {
		String name = nameBox.getValue().isBlank() ? "poison" : nameBox.getValue();
		int turns = parseIntOr(turnsBox.getValue(), 3);
		DndsheetsMod.PACKET_HANDLER.sendToServer(new TurnEffectApplyMessage(targetUuid, name, dice.value(), turns));
	}
}
