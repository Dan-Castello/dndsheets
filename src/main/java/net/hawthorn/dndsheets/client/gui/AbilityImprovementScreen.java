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
 * <p>Mejora de Puntuación de Característica: la elección que da un nivel 4, 8, 12, 16 o 19.</p>
 *
 * <p>Un solo panel con las seis características y dos modos, en vez de un asistente por pasos: la elección
 * de 5e es "+2 a una <b>o</b> +1 a dos", y partirla en pantallas obligaría a volver atrás para cambiar de
 * idea sobre algo que cabe entero delante de los ojos.</p>
 *
 * <p>Cada botón enseña la puntuación actual y a cuánto subiría, porque la decisión no se toma sobre el
 * nombre de la característica sino sobre si el modificador cruza un número par — subir Destreza de 15 a 16
 * da +1 al modificador y de 16 a 17 no da nada, y eso no se ve si la pantalla solo dice "Destreza".</p>
 */
public class AbilityImprovementScreen extends ModalDialogScreen {
	private static final int WIDTH = 280;
	private static final int HEIGHT = 176;
	private static final int MAX_ABILITY = 20;

	private static final String[] KEYS = {"strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"};
	private static final String[] LABELS = {"Fuerza", "Destreza", "Constitución", "Inteligencia", "Sabiduría", "Carisma"};

	/** null = todavía no eligió la primera; con una elegida, la pantalla pide la segunda o confirma el +2. */
	private String firstPick;
	private final Button[] abilityButtons = new Button[KEYS.length];

	protected AbilityImprovementScreen() {
		super(Component.literal("Mejora de Característica"), WIDTH, HEIGHT);
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

		//"+2 a la elegida" solo tiene sentido con una ya elegida; hasta entonces el botón está ahí pero
		//apagado, en vez de aparecer de golpe y mover el resto de la pantalla bajo el ratón.
		Button confirm = addModalButton(16, 116, 248, 20, Component.literal("Confirmar +2 a la elegida"), button -> {
			if (firstPick == null) return;
			send(firstPick, "");
		});
		confirm.active = false;
		this.confirmButton = confirm;

		//La dote es la OTRA cara de esta misma elección en 5e ("+2 a una, +1 a dos, o una dote"), así que
		//va en esta pantalla y no en otra: separarlas dejaría al jugador eligiendo sin ver la alternativa.
		addModalButton(16, 140, 248, 20, Component.literal("...o coge una dote"), button -> {
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
			//Volver a pulsar la misma la deselecciona: es la salida obvia de "me equivoqué", y sin ella
			//habría que cerrar la pantalla y esperar a que el servidor la reabra.
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
			//Una característica ya en 20 no puede subir: se deja visible y apagada para que se vea POR QUÉ
			//no es una opción, en lugar de desaparecer y dejar un hueco sin explicación.
			abilityButtons[i].active = scoreOf(KEYS[i]) < MAX_ABILITY;
		}
		if (confirmButton != null) confirmButton.active = firstPick != null;
	}

	private String labelFor(int index) {
		int score = scoreOf(KEYS[index]);
		String marca = KEYS[index].equals(firstPick) ? "> " : "";
		int subida = firstPick == null ? 2 : 1;
		int nuevo = Math.min(MAX_ABILITY, score + subida);
		return marca + LABELS[index] + " " + score + " → " + nuevo;
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
			? Component.literal("Elige: +2 a una, o +1 a dos distintas")
			: Component.literal("Ahora elige la segunda, o confirma el +2");
		guiGraphics.drawCenteredString(this.font, titulo, this.width / 2, dialogTop() + 8, 0xFFFFFF);
		guiGraphics.drawCenteredString(this.font, Component.literal("El máximo de una característica es 20."),
			this.width / 2, dialogTop() + 20, GuiStyle.MUTED_COLOR);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
