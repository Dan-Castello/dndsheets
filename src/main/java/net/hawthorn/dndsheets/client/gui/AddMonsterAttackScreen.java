package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.AddCustomAttackMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Formulario para darle un ataque nuevo a UN monstruo ya invocado (ver
 * {@link net.hawthorn.dndsheets.MonsterRegistry#addCustomAttack}), abierto desde el botón
 * "+ Añadir ataque" de {@link MonsterActionScreen}. Habilidad de ataque/daño y tipo de daño se eligen
 * con botones cíclicos en vez de texto libre, para no depender de acordarse de escribir "str"/"dex" bien
 * — nombre y dado siguen siendo texto porque no tienen un catálogo fijo de opciones.</p>
 */
public class AddMonsterAttackScreen extends Screen {
	private static final String[] ABILITIES = {"str", "dex", "con", "int", "wis", "cha"};
	private static final String[] DAMAGE_TYPES = {
		"fisico", "cortante", "perforante", "contundente", "fuego", "frio", "rayo",
		"acido", "veneno", "psiquico", "radiante", "necrotico", "fuerza", "trueno"
	};

	private static final int FIELD_WIDTH = 160;
	private static final int FIELD_HEIGHT = 20;
	private static final int ROW_HEIGHT = 26;

	private final int entityId;
	private EditBox nameBox;
	private EditBox diceBox;
	private int toHitIndex = 0;
	private int damageAbilityIndex = 0;
	private int damageTypeIndex = 0;
	private Button toHitButton;
	private Button damageAbilityButton;
	private Button damageTypeButton;

	private AddMonsterAttackScreen(int entityId) {
		super(Component.literal("Añadir ataque"));
		this.entityId = entityId;
	}

	public static void open(int entityId) {
		Minecraft.getInstance().setScreen(new AddMonsterAttackScreen(entityId));
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int y = this.height / 2 - ROW_HEIGHT * 3;

		nameBox = new EditBox(this.font, centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Nombre"));
		nameBox.setValue("Ataque");
		nameBox.setMaxLength(40);
		this.addWidget(nameBox);
		this.setInitialFocus(nameBox);
		y += ROW_HEIGHT;

		diceBox = new EditBox(this.font, centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Dado"));
		diceBox.setValue("1d6");
		diceBox.setMaxLength(20);
		this.addWidget(diceBox);
		y += ROW_HEIGHT;

		toHitButton = this.addRenderableWidget(Button.builder(cycleLabel("Ataque con", ABILITIES[toHitIndex]), button -> {
			toHitIndex = (toHitIndex + 1) % ABILITIES.length;
			toHitButton.setMessage(cycleLabel("Ataque con", ABILITIES[toHitIndex]));
		}).bounds(centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT).build());
		y += ROW_HEIGHT;

		damageAbilityButton = this.addRenderableWidget(Button.builder(cycleLabel("Daño con", ABILITIES[damageAbilityIndex]), button -> {
			damageAbilityIndex = (damageAbilityIndex + 1) % ABILITIES.length;
			damageAbilityButton.setMessage(cycleLabel("Daño con", ABILITIES[damageAbilityIndex]));
		}).bounds(centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT).build());
		y += ROW_HEIGHT;

		damageTypeButton = this.addRenderableWidget(Button.builder(cycleLabel("Tipo", DAMAGE_TYPES[damageTypeIndex]), button -> {
			damageTypeIndex = (damageTypeIndex + 1) % DAMAGE_TYPES.length;
			damageTypeButton.setMessage(cycleLabel("Tipo", DAMAGE_TYPES[damageTypeIndex]));
		}).bounds(centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT).build());
		y += ROW_HEIGHT;

		this.addRenderableWidget(Button.builder(Component.literal("Confirmar"), button -> {
			String name = nameBox.getValue().isBlank() ? "Ataque" : nameBox.getValue();
			String dice = diceBox.getValue().isBlank() ? "1d6" : diceBox.getValue();
			DndsheetsMod.PACKET_HANDLER.sendToServer(new AddCustomAttackMessage(entityId, name, ABILITIES[toHitIndex], dice, ABILITIES[damageAbilityIndex], DAMAGE_TYPES[damageTypeIndex]));
			this.onClose();
		}).bounds(centerX - FIELD_WIDTH / 2, y, FIELD_WIDTH / 2 - 2, FIELD_HEIGHT).build());

		this.addRenderableWidget(Button.builder(Component.literal("Cancelar"), button -> this.onClose())
			.bounds(centerX + 2, y, FIELD_WIDTH / 2 - 2, FIELD_HEIGHT).build());
	}

	private static Component cycleLabel(String prefix, String value) {
		return Component.literal(prefix + ": " + value);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		guiGraphics.drawCenteredString(this.font, title, this.width / 2, this.height / 2 - ROW_HEIGHT * 3 - 16, 0xFFFFFF);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		nameBox.render(guiGraphics, mouseX, mouseY, partialTicks);
		diceBox.render(guiGraphics, mouseX, mouseY, partialTicks);
	}

	@Override
	public void tick() {
		nameBox.tick();
		diceBox.tick();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
