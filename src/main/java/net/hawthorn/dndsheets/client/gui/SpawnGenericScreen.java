package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.SpawnGenericMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>NPC en blanco desde el Panel de DM (equivalente en GUI a {@code /dndmonsters spawn generic}): sin
 * ataques, pensado para rellenarlo después desde el menú de la Vara de DM ("+ Añadir ataque", ver
 * {@link AddMonsterAttackScreen}). Se invoca en la posición del DM.</p>
 */
public class SpawnGenericScreen extends Screen {
	private static final int FIELD_WIDTH = 160;
	private static final int FIELD_HEIGHT = 20;
	private static final int ROW_HEIGHT = 26;

	private EditBox nameBox;
	private EditBox baseEntityBox;
	private EditBox acBox;
	private EditBox hpBox;

	private SpawnGenericScreen() {
		super(Component.literal("NPC genérico"));
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new SpawnGenericScreen());
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int y = this.height / 2 - ROW_HEIGHT * 2;

		nameBox = new EditBox(this.font, centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Nombre"));
		nameBox.setValue("NPC");
		nameBox.setMaxLength(40);
		this.addWidget(nameBox);
		this.setInitialFocus(nameBox);
		y += ROW_HEIGHT;

		baseEntityBox = new EditBox(this.font, centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Entidad base"));
		baseEntityBox.setValue("minecraft:villager");
		baseEntityBox.setMaxLength(64);
		this.addWidget(baseEntityBox);
		y += ROW_HEIGHT;

		acBox = new EditBox(this.font, centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("CA"));
		acBox.setValue("10");
		acBox.setMaxLength(3);
		this.addWidget(acBox);
		y += ROW_HEIGHT;

		hpBox = new EditBox(this.font, centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("PG"));
		hpBox.setValue("10");
		hpBox.setMaxLength(4);
		this.addWidget(hpBox);
		y += ROW_HEIGHT;

		this.addRenderableWidget(Button.builder(Component.literal("Invocar"), button -> {
			String name = nameBox.getValue().isBlank() ? "NPC" : nameBox.getValue();
			String baseEntity = baseEntityBox.getValue().isBlank() ? "minecraft:villager" : baseEntityBox.getValue();
			int ac = parseIntOr(acBox.getValue(), 10);
			int hp = Math.max(1, parseIntOr(hpBox.getValue(), 10));
			DndsheetsMod.PACKET_HANDLER.sendToServer(new SpawnGenericMessage(name, baseEntity, ac, hp));
			this.onClose();
		}).bounds(centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH / 2 - 2, FIELD_HEIGHT).build());

		this.addRenderableWidget(Button.builder(Component.literal("Cancelar"), button -> this.onClose())
			.bounds(centerX + 2, y, FIELD_WIDTH / 2 - 2, FIELD_HEIGHT).build());
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
		baseEntityBox.render(guiGraphics, mouseX, mouseY, partialTicks);
		acBox.render(guiGraphics, mouseX, mouseY, partialTicks);
		hpBox.render(guiGraphics, mouseX, mouseY, partialTicks);
	}

	@Override
	public void tick() {
		nameBox.tick();
		baseEntityBox.tick();
		acBox.tick();
		hpBox.tick();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
