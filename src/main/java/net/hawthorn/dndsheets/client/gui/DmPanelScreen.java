package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.PresetListRequestMessage;
import net.hawthorn.dndsheets.network.SheetSummaryRequestMessage;
import net.hawthorn.dndsheets.network.TraitListRequestMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Punto de entrada del DM a todo lo que antes solo eran comandos: turnos, invocar un NPC en blanco,
 * conceder un rasgo. Se abre con la tecla de acceso rápido (ver
 * {@link net.hawthorn.dndsheets.init.DndsheetsModKeyMappings#DM_PANEL}), que ya comprueba permisos de
 * operador antes de abrir esto — dar/quitar ataques a un monstruo concreto sigue viviendo en su propio
 * menú (clic derecho con la Vara de DM, ver {@link MonsterActionScreen}), porque ese ya necesita el
 * monstruo señalado y no tiene sentido pedirlo aparte aquí.</p>
 */
public class DmPanelScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int SPACING = 4;

	private DmPanelScreen() {
		super(Component.literal("Panel de DM"));
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new DmPanelScreen());
	}

	@Override
	protected void init() {
		String[] labels = {"Modo turnos", "Invocar NPC genérico", "Conceder rasgo", "Ajustes de hoja", "Aplicar preset a jugador"};
		Runnable[] actions = {
			TurnControlScreen::open,
			SpawnGenericScreen::open,
			() -> PlayerPickerScreen.open("Elige a quién conceder el rasgo",
				uuid -> DndsheetsMod.PACKET_HANDLER.sendToServer(new TraitListRequestMessage(uuid))),
			() -> PlayerPickerScreen.open("Elige a quién ajustar la hoja",
				uuid -> DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetSummaryRequestMessage(uuid))),
			() -> PlayerPickerScreen.open("Elige a quién aplicar el preset",
				uuid -> DndsheetsMod.PACKET_HANDLER.sendToServer(new PresetListRequestMessage(uuid))),
		};

		int totalHeight = labels.length * (BUTTON_HEIGHT + SPACING);
		int startY = (this.height - totalHeight) / 2;

		for (int i = 0; i < labels.length; i++) {
			Runnable action = actions[i];
			this.addRenderableWidget(Button.builder(Component.literal(labels[i]), button -> action.run())
				.bounds((this.width - BUTTON_WIDTH) / 2, startY + i * (BUTTON_HEIGHT + SPACING), BUTTON_WIDTH, BUTTON_HEIGHT).build());
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
