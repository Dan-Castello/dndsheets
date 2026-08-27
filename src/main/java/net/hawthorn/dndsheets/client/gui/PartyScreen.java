package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.SheetSummaryRequestMessage;
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
 * <p>Pulsar la fila de un jugador abre sus Ajustes de hoja directamente (el mismo camino que Panel de
 * DM → Ajustes → elegir jugador, sin el paso de elegir): esta es la pantalla que el DM más mira en
 * combate, y "lo veo pero para tocarlo tengo que salir y navegar tres listas" era la queja concreta.
 * Es un ATAJO al flujo existente, no un segundo camino que mantener — la fila manda el mismo
 * {@link SheetSummaryRequestMessage} que mandaba el selector de jugador. Los PNJ llegan con id vacío
 * (no son un jugador conectado que Ajustes pueda resolver) y su fila sigue siendo solo lectura.</p>
 */
public class PartyScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<Component> rows;

	private PartyScreen(List<String> ids, List<Component> rows, Screen parent) {
		super(Component.translatable("gui.dndsheets.party.title"), parent);
		this.ids = ids;
		this.rows = rows;
	}

	public static void open(List<String> ids, List<Component> rows) {
		Minecraft.getInstance().setScreen(new PartyScreen(ids, rows, Minecraft.getInstance().screen));
	}

	//Más ancha que la lista estándar: cada fila lleva nombre, PG, CA y condiciones, y a 200px se cortaba.
	@Override
	protected int buttonWidth() {
		return 260;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < rows.size(); i++) {
			String id = i < ids.size() ? ids.get(i) : "";
			if (id.isEmpty()) {
				addRow(rows.get(i).copy().withStyle(ChatFormatting.GRAY), button -> {});
			} else {
				addRow(rows.get(i), button -> DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetSummaryRequestMessage(id)));
			}
		}
	}

	@Override
	protected Component emptyMessage() {
		return rows.isEmpty() ? Component.translatable("gui.dndsheets.party.empty") : null;
	}
}
