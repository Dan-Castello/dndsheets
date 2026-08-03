package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.TurnEffectApplyMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Formulario para aplicar un efecto de estado (veneno, etc.) a UN jugador ya elegido en
 * {@link PlayerPickerScreen}, abierto desde el botón "Aplicar efecto" de {@link TurnControlScreen}
 * (equivalente en GUI a {@code /dndturns effect} — ver AUDIT_UX.md, DM #4). El dado se elige con un
 * botón cíclico sobre las mismas sugerencias que ya usa {@code TurnCommand}, en vez de texto libre.</p>
 */
public class AddTurnEffectScreen extends Screen {
	private static final String[] DICE_OPTIONS = {"1d4", "1d6", "1d8", "1d10", "1d12", "2d6", "2d8"};

	private static final int FIELD_WIDTH = 160;
	private static final int FIELD_HEIGHT = 20;
	private static final int ROW_HEIGHT = 26;

	private final String targetUuid;
	private EditBox nameBox;
	private EditBox turnsBox;
	private int diceIndex = 0;
	private Button diceButton;

	private AddTurnEffectScreen(String targetUuid) {
		super(Component.literal("Aplicar efecto"));
		this.targetUuid = targetUuid;
	}

	public static void open(String targetUuid) {
		Minecraft.getInstance().setScreen(new AddTurnEffectScreen(targetUuid));
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int y = this.height / 2 - ROW_HEIGHT * 2;

		nameBox = new EditBox(this.font, centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Nombre"));
		nameBox.setValue("veneno");
		nameBox.setMaxLength(40);
		this.addWidget(nameBox);
		this.setInitialFocus(nameBox);
		y += ROW_HEIGHT;

		diceButton = this.addRenderableWidget(Button.builder(cycleLabel("Dado", DICE_OPTIONS[diceIndex]), button -> {
			diceIndex = (diceIndex + 1) % DICE_OPTIONS.length;
			diceButton.setMessage(cycleLabel("Dado", DICE_OPTIONS[diceIndex]));
		}).bounds(centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT).build());
		y += ROW_HEIGHT;

		turnsBox = new EditBox(this.font, centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Turnos"));
		turnsBox.setValue("3");
		turnsBox.setMaxLength(2);
		this.addWidget(turnsBox);
		y += ROW_HEIGHT;

		this.addRenderableWidget(Button.builder(Component.literal("Confirmar"), button -> {
			String name = nameBox.getValue().isBlank() ? "veneno" : nameBox.getValue();
			int turns = parseIntOr(turnsBox.getValue(), 3);
			DndsheetsMod.PACKET_HANDLER.sendToServer(new TurnEffectApplyMessage(targetUuid, name, DICE_OPTIONS[diceIndex], turns));
			this.onClose();
		}).bounds(centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH / 2 - 2, FIELD_HEIGHT).build());

		this.addRenderableWidget(Button.builder(Component.literal("Cancelar"), button -> this.onClose())
			.bounds(centerX + 2, y, FIELD_WIDTH / 2 - 2, FIELD_HEIGHT).build());
	}

	private static Component cycleLabel(String prefix, String value) {
		return Component.literal(prefix + ": " + value);
	}

	private static int parseIntOr(String value, int fallback) {
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		guiGraphics.drawCenteredString(this.font, title, this.width / 2, this.height / 2 - ROW_HEIGHT * 2 - 16, 0xFFFFFF);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		nameBox.render(guiGraphics, mouseX, mouseY, partialTicks);
		turnsBox.render(guiGraphics, mouseX, mouseY, partialTicks);
	}

	@Override
	public void tick() {
		nameBox.tick();
		turnsBox.tick();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
