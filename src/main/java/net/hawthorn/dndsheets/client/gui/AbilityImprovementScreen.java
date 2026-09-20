package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.network.AbilityImprovementMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import com.google.gson.JsonObject;

/**
 * <p>Ability Score Improvement: the choice granted at level 4, 8, 12, 16, or 19.</p>
 *
 * <p>A single panel with the six abilities and two modes, instead of a step-by-step wizard: the 5e
 * choice is "+2 to one <b>or</b> +1 to two", and splitting it across screens would force going back to
 * change your mind about something that fits entirely in front of your eyes.</p>
 *
 * <p>Each button shows the current score and what it would rise to, because the decision isn't made on
 * the ability's name but on whether the modifier crosses an even number — raising Dexterity from 15 to
 * 16 gives +1 to the modifier and 16 to 17 gives nothing, and that isn't visible if the screen only says
 * "Dexterity".</p>
 */
public class AbilityImprovementScreen extends ModalDialogScreen {
	private static final int WIDTH = 280;
	private static final int HEIGHT = 176;
	private static final int MAX_ABILITY = 20;

	private static final String[] KEYS = {"strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"};
	private static final String[] SHORT = {"str", "dex", "con", "int", "wis", "cha"};

	/** null = hasn't picked the first one yet; with one picked, the screen asks for the second or confirms the +2. */
	private String firstPick;
	private final Button[] abilityButtons = new Button[KEYS.length];

	protected AbilityImprovementScreen() {
		super(Component.translatable("gui.dndsheets.ability_improvement.title"), WIDTH, HEIGHT);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new AbilityImprovementScreen());
	}

	@Override
	protected void init() {
		for (int i = 0; i < KEYS.length; i++) {
			int column = i % 2;
			int row = i / 2;
			int index = i;
			abilityButtons[i] = addModalButton(16 + column * 126, 34 + row * 24, 122, 20,
				Component.literal(labelFor(index)), button -> pick(KEYS[index]));
		}

		//"+2 to the chosen one" only makes sense once one is already chosen; until then the button is there
		//but disabled, instead of popping up suddenly and shifting the rest of the screen under the cursor.
		Button confirm = addModalButton(16, 116, 248, 20, Component.translatable("gui.dndsheets.ability_improvement.confirm"), button -> {
			if (firstPick == null) return;
			send(firstPick, "");
		});
		confirm.active = false;
		this.confirmButton = confirm;

		//The feat is the OTHER side of this same choice in 5e ("+2 to one, +1 to two, or a feat"), so it
		//belongs on this screen and not another: separating them would leave the player choosing without
		//seeing the alternative.
		addModalButton(16, 140, 248, 20, Component.translatable("gui.dndsheets.ability_improvement.take_feat"), button -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(
				new net.hawthorn.dndsheets.network.BrowseActionMessage(
					net.hawthorn.dndsheets.network.BrowseActionMessage.Action.LIST_FEATS));
			this.onClose();
		});
		refresh();
	}

	private Button confirmButton;

	private void pick(String ability) {
		if (firstPick == null) {
			firstPick = ability;
			refresh();
			return;
		}
		if (firstPick.equals(ability)) {
			//Clicking the same one again deselects it: it's the obvious way out of "I made a mistake", and
			//without it you'd have to close the screen and wait for the server to reopen it.
			firstPick = null;
			refresh();
			return;
		}
		send(firstPick, ability);
	}

	private void send(String first, String second) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new AbilityImprovementMessage(first, second));
		this.onClose();
	}

	private void refresh() {
		for (int i = 0; i < KEYS.length; i++) {
			abilityButtons[i].setMessage(Component.literal(labelFor(i)));
			//An ability already at 20 can't be raised: it's left visible and disabled so it's clear WHY it
			//isn't an option, instead of disappearing and leaving an unexplained gap.
			abilityButtons[i].active = scoreOf(KEYS[i]) < MAX_ABILITY;
		}
		if (confirmButton != null) confirmButton.active = firstPick != null;
	}

	private String labelFor(int index) {
		int score = scoreOf(KEYS[index]);
		String marca = KEYS[index].equals(firstPick) ? "> " : "";
		int subida = firstPick == null ? 2 : 1;
		int nuevo = Math.min(MAX_ABILITY, score + subida);
		return marca + net.minecraft.client.resources.language.I18n.get("gui.dndsheets.character_sheet.ability_" + SHORT[index]) + " " + score + " → " + nuevo;
	}

	private static int scoreOf(String ability) {
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null || !sheet.has(ability)) return 10;
		try {
			return Integer.parseInt(sheet.get(ability).getAsString());
		} catch (RuntimeException e) {
			return 10;
		}
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderPanel(guiGraphics);
		Component titulo = firstPick == null
			? Component.translatable("gui.dndsheets.ability_improvement.hint_first")
			: Component.translatable("gui.dndsheets.ability_improvement.hint_second");
		guiGraphics.drawCenteredString(this.font, titulo, this.width / 2, dialogTop() + 8, 0xFFFFFF);
		guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.ability_improvement.cap"),
			this.width / 2, dialogTop() + 20, GuiStyle.MUTED_COLOR);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
