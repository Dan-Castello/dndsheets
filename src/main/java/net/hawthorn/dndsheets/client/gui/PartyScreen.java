package net.hawthorn.dndsheets.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Vista de grupo del DM: cada jugador conectado con su personaje, PG, CA y condiciones activas, de un
 * vistazo. Hasta ahora eso solo se podía consultar de uno en uno, abriendo los Ajustes de hoja de cada
 * jugador por separado — inservible en mitad de un combate, que es justo cuando hace falta.</p>
 *
 * <p>Solo lectura: las filas no hacen nada al pulsarlas. Actuar sobre un jugador concreto ya tiene su
 * camino (Panel de DM → Ajustes de hoja), y duplicarlo aquí sería una segunda forma de hacer lo mismo
 * que habría que mantener en paralelo.</p>
 */
public class PartyScreen extends ListPickerScreen {

	private final List<String> rows;

	private PartyScreen(List<String> rows, Screen parent) {
		super(Component.translatable("gui.dndsheets.party.title"), parent);
		this.rows = rows;
	}

	public static void open(List<String> rows) {
		Minecraft.getInstance().setScreen(new PartyScreen(rows, Minecraft.getInstance().screen));
	}

	//Más ancha que la lista estándar: cada fila lleva nombre, PG, CA y condiciones, y a 200px se cortaba.
	@Override
	protected int buttonWidth() {
		return 260;
	}

	@Override
	protected void buildRows() {
		for (String row : rows) {
			addRow(Component.literal(row).withStyle(ChatFormatting.GRAY), button -> {});
		}
	}

	@Override
	protected Component emptyMessage() {
		return rows.isEmpty() ? Component.translatable("gui.dndsheets.party.empty") : null;
	}
}
