package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Crear un personaje escribiéndole el nombre. Era la última pieza de la gestión de personajes que
 * todavía exigía un comando: se podía cambiar, borrar y subir de nivel desde la pantalla, pero para tener
 * uno nuevo había que saberse {@code /dndchar new}.</p>
 *
 * <p>Solo pide el nombre. Clase, características y todo lo demás salen del preset que se elija después
 * desde la hoja, así que preguntarlo aquí sería preguntar dos veces por lo mismo — y con peor información,
 * porque la pantalla de presets enseña lo que concede cada uno.</p>
 */
public class NewCharacterScreen extends SmallFormScreen {

	private EditBox nameBox;

	private NewCharacterScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.new_character.title"), 1, parent);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new NewCharacterScreen(Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		//Sin valor por defecto: un "Personaje" ya escrito se queda tal cual en cuanto alguien pulse
		//Confirmar sin mirar, y dos personajes con el mismo nombre son justo lo que cuesta distinguir.
		nameBox = addField(Component.translatable("gui.dndsheets.new_character.name").getString(), "", 40);
	}

	@Override
	protected void onConfirm() {
		String name = nameBox.getValue().trim();
		//Un nombre vacío no se manda: el servidor lo rechazaría igual, y un viaje de ida y vuelta para que
		//no pase nada se lee como que el botón está roto.
		if (name.isEmpty()) return;
		DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.CREATE, name));
	}
}
