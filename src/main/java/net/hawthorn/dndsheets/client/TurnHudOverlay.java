package net.hawthorn.dndsheets.client;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.client.gui.GuiStyle;
import net.hawthorn.dndsheets.network.TurnStateMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>HUD del modo turnos: no solo de quién es el turno (eso ya lo tenía), sino el tablero de iniciativa
 * entero — orden, quién ya actuó, quién cayó, qué condiciones lleva encima cada uno — con el mismo aspecto
 * de tomo encuadernado que el resto de la interfaz del mod (ver {@link GuiStyle}), en vez de texto flotando
 * sin panel.</p>
 *
 * <p>Antes esta información existía, pero solo en el chat: cada tirada, cada condición aplicada, cada
 * "le toca a X" era una línea de texto más en una lista que crece sola durante todo el combate. Un jugador
 * nuevo no tiene forma de saber, sin desplazarse hacia arriba, quién sigue en pie o qué le está pasando a
 * su personaje ahora mismo. Este panel no reemplaza el chat —los mensajes siguen— pero da el estado actual
 * de un vistazo, que es como se lee una mesa real.</p>
 *
 * <p>Movimiento y estado de acción propio se calculan en el cliente contra el origen del turno que ya
 * manda {@link net.hawthorn.dndsheets.network.TurnStateMessage}, sin pedirle nada más al servidor; el resto
 * del tablero (nombres, condiciones, quién actuó) sí viaja en ese mismo mensaje porque es información de
 * OTROS combatientes que el cliente no tiene forma de calcular solo.</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class TurnHudOverlay {
	private static final int DEFAULT_SPEED_FEET = 30;
	private static final double FEET_PER_BLOCK = 5.0;
	//speedBlocksFromClientSheet corre cada frame mientras es el turno del jugador local: el Pattern se
	//cachea en vez de recompilarse en cada frame.
	private static final Pattern SPEED_FEET_PATTERN = Pattern.compile("\\d+");

	//Achicado a pedido: el panel ocupaba demasiada pantalla. PADDING no baja de 7 aunque el resto se
	//apriete — con menos, el texto se mete debajo de la cantonera de latón de la esquina (ver GuiStyle:
	//brazo de 3px + 2px de margen = 5px mínimos para no pisarla).
	private static final int PANEL_WIDTH = 176;
	private static final int PADDING = 7;
	private static final int ROW_HEIGHT = 10;
	//Antes 10: con un encuentro grande el panel se volvía enorme antes de que la ventana centrada (ver
	//render) entrara a recortar. Con 6, la mayoría de los combates entra entero y uno grande se recorta antes.
	private static final int MAX_ROSTER_ROWS = 6;

	private static final int MOVEMENT_COLOR = 0xFF6FD1C6;
	private static final int MOVEMENT_TRACK_COLOR = 0xFF3A342A;
	private static final int GOOD_COLOR = 0xFF6FCB6F;
	private static final int ENEMY_COLOR = 0xFFCC7A6E;
	private static final int CONDITION_COLOR = 0xFFB08A5A;

	@SubscribeEvent
	public static void registerOverlays(RegisterGuiOverlaysEvent event) {
		event.registerAboveAll("dndsheets_turns", (gui, guiGraphics, partialTick, width, height) -> render(guiGraphics, width));
	}

	private static void render(GuiGraphics guiGraphics, int screenWidth) {
		if (!TurnHudState.active()) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) return;
		Font font = minecraft.font;

		List<TurnStateMessage.RosterRow> roster = TurnHudState.roster();
		TurnStateMessage.RosterRow myRow = TurnHudState.myRow(minecraft.player.getId());

		//Ventana centrada en quien tiene el turno: con un enjambre o un combate de muchos enemigos, el
		//techo de filas no basta solo — sin esto, si el turno activo caía después de la fila 10 el panel
		//lo dejaba fuera de vista sin avisar, y el HUD parecía "atascado" en un combatiente que ya pasó.
		int total = roster.size();
		boolean truncated = total > MAX_ROSTER_ROWS;
		int windowCap = truncated ? MAX_ROSTER_ROWS - 1 : MAX_ROSTER_ROWS; //Una fila menos si hace falta el indicador de "+N ocultos".
		int currentIdx = indexOfCurrent(roster);
		int start = truncated ? Math.max(0, Math.min(currentIdx - windowCap / 2, total - windowCap)) : 0;
		int end = truncated ? start + windowCap : total;
		int hidden = total - (end - start);

		//Dos pasadas: primero se mide cuánto panel hace falta, después se dibuja el fondo, después el
		//contenido encima — GuiStyle.panel necesita saber el borde inferior ANTES de que haya nada que pintar.
		int top = 8;
		int left = screenWidth - 8 - PANEL_WIDTH;
		int right = screenWidth - 8;
		int contentTop = top + PADDING;

		int height = ROW_HEIGHT + 4 + (end - start) * ROW_HEIGHT + (truncated ? ROW_HEIGHT : 0);
		if (myRow != null) height += 4 + 3 * ROW_HEIGHT;
		int bottom = contentTop + height + PADDING - 4;

		GuiStyle.panel(guiGraphics, left, top, right, bottom);

		int textLeft = left + PADDING;
		int textRight = right - PADDING;
		int y = contentTop;

		guiGraphics.drawString(font, "Ronda " + TurnHudState.round(), textLeft, y, GuiStyle.ACCENT_COLOR);
		y += ROW_HEIGHT;
		GuiStyle.rule(guiGraphics, textLeft, textRight, y);
		y += 4;

		for (int i = start; i < end; i++) {
			drawRosterRow(guiGraphics, font, roster.get(i), textLeft, textRight, y,
				roster.get(i).entityId() == TurnHudState.currentEntityId());
			y += ROW_HEIGHT;
		}
		if (truncated) {
			String more = "+" + hidden + (hidden == 1 ? " combatiente oculto" : " combatientes ocultos");
			guiGraphics.drawString(font, more, textLeft, y, GuiStyle.MUTED_COLOR);
			y += ROW_HEIGHT;
		}

		if (myRow != null) {
			GuiStyle.rule(guiGraphics, textLeft, textRight, y);
			y += 4;
			drawEconomy(guiGraphics, font, myRow, minecraft, textLeft, textRight, y);
		}
	}

	//0 si no se encuentra (combate recién arrancado, primer paquete): mejor centrar la ventana en el
	//principio del orden que reventar con un índice inválido.
	private static int indexOfCurrent(List<TurnStateMessage.RosterRow> roster) {
		int currentEntityId = TurnHudState.currentEntityId();
		for (int i = 0; i < roster.size(); i++) {
			if (roster.get(i).entityId() == currentEntityId) return i;
		}
		return 0;
	}

	//Una fila de iniciativa: "▶ Nombre" si le toca ahora, tachado y apagado si cayó, y un sufijo a la
	//derecha con lo más urgente de saber sobre esa fila — condiciones si tiene, si no un check discreto de
	//"ya actuó este asalto". Las dos cosas comparten el mismo hueco a propósito: mostrar ambas duplicaría
	//el alto del panel por cada combatiente, y la condición es siempre la más importante de las dos.
	private static void drawRosterRow(GuiGraphics guiGraphics, Font font, TurnStateMessage.RosterRow row,
									   int left, int right, int y, boolean isCurrent) {
		String marker = isCurrent ? "▶ " : "   ";
		int nameColor = row.defeated() ? GuiStyle.MUTED_COLOR
			: isCurrent ? GuiStyle.TITLE_COLOR
			: row.isMonster() ? ENEMY_COLOR : GuiStyle.SUBTITLE_COLOR;

		net.minecraft.network.chat.MutableComponent name = Component.literal(marker + row.name());
		if (row.defeated()) name = name.withStyle(ChatFormatting.STRIKETHROUGH);
		guiGraphics.drawString(font, name, left, y, nameColor);

		String suffix = null;
		int suffixColor = CONDITION_COLOR;
		if (row.defeated()) {
			suffix = null; //Ya lo dice el tachado; repetir "derrotado" al lado sería ruido.
		} else if (!row.conditions().isEmpty()) {
			suffix = row.conditions().size() == 1 ? row.conditions().get(0)
				: row.conditions().get(0) + " +" + (row.conditions().size() - 1);
		} else if (row.acted() && !isCurrent) {
			suffix = "✓";
			suffixColor = GuiStyle.MUTED_COLOR;
		}
		if (suffix != null) {
			guiGraphics.drawString(font, suffix, right - font.width(suffix), y, suffixColor);
		}
	}

	//El propio estado de acción/reacción/movimiento: lo único de este HUD que de verdad importa decidir
	//(el resto del tablero es información, esto es "qué me queda por gastar"). Se muestra siempre que el
	//jugador tenga puesto en el orden, no solo en su turno — la reacción se puede gastar fuera de turno.
	private static void drawEconomy(GuiGraphics guiGraphics, Font font, TurnStateMessage.RosterRow myRow,
									 Minecraft minecraft, int left, int right, int y) {
		boolean myTurn = myRow.entityId() == TurnHudState.currentEntityId();
		boolean actionAvailable = myTurn && !TurnHudState.actionUsed();

		drawEconomyLine(guiGraphics, font, "Acción", actionAvailable, myTurn, left, right, y);
		y += ROW_HEIGHT;
		drawEconomyLine(guiGraphics, font, "Reacción", !myRow.reactionUsed(), true, left, right, y);
		y += ROW_HEIGHT;

		double speedBlocks = speedBlocksFromClientSheet();
		double distanceMoved = minecraft.player.position().distanceTo(new Vec3(TurnHudState.originX(), TurnHudState.originY(), TurnHudState.originZ()));
		double remaining = Math.max(0, speedBlocks - distanceMoved);
		String moveText = "Movimiento: " + Math.round(remaining) + "/" + Math.round(speedBlocks);
		guiGraphics.drawString(font, moveText, left, y, MOVEMENT_COLOR);

		int barWidth = right - left;
		int barY = y + 9;
		double fraction = speedBlocks <= 0 ? 0 : Math.min(1.0, remaining / speedBlocks);
		guiGraphics.fill(left, barY, right, barY + 2, MOVEMENT_TRACK_COLOR);
		guiGraphics.fill(left, barY, left + (int) Math.round(barWidth * fraction), barY + 2, MOVEMENT_COLOR);
	}

	//"disponible" solo se pinta en verde cuando de verdad se puede gastar ya mismo (la reacción siempre que
	//no se haya usado; la acción solo en el propio turno) — fuera de eso, apagado, para no prometer un
	//clic que TurnManager va a rechazar.
	private static void drawEconomyLine(GuiGraphics guiGraphics, Font font, String label, boolean available,
										 boolean usable, int left, int right, int y) {
		guiGraphics.drawString(font, label, left, y, GuiStyle.SUBTITLE_COLOR);
		String state = available ? "disponible" : usable ? "usada" : "—";
		int color = available ? GOOD_COLOR : GuiStyle.MUTED_COLOR;
		guiGraphics.drawString(font, state, right - font.width(state), y, color);
	}

	//Misma conversión que MovementAnchorTracker.speedBlocksFor en el servidor, pero leída de la hoja ya sincronizada
	//al cliente (SheetLoader.getClientSheet()) — el HUD no necesita pedirle nada nuevo al servidor.
	private static double speedBlocksFromClientSheet() {
		JsonObject sheet = SheetLoader.getClientSheet();
		int feet = DEFAULT_SPEED_FEET;
		if (sheet != null && sheet.has("speed")) {
			Matcher matcher = SPEED_FEET_PATTERN.matcher(sheet.get("speed").getAsString());
			if (matcher.find()) {
				try { feet = Integer.parseInt(matcher.group()); } catch (NumberFormatException ignored) {}
			}
		}
		return feet / FEET_PER_BLOCK;
	}
}
