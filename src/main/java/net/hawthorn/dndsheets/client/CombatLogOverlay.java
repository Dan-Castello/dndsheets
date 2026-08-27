package net.hawthorn.dndsheets.client;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.GuiStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>Resumen efímero de los últimos eventos de combate (tiradas, ataques, conjuros, salvaciones de
 * muerte), abajo a la derecha, con el mismo aspecto de tomo que el resto del HUD (ver
 * {@link GuiStyle}). <b>Una línea por evento, recortada con elipsis, nunca envuelta</b>: el detalle
 * completo (desglose del dado, contra qué CA, tipo de daño) vive SOLO en el chat — tenerlo entero en
 * los dos sitios era leer lo mismo dos veces, y este panel existe para el vistazo, no para la lectura.</p>
 *
 * <p>No hay mensaje de red nuevo ni cambios en ningún manager: TODA línea de combate del mod ya llega
 * al cliente como chat y ya empieza por una etiqueta traducible {@code chat.dndsheets.tag.*} (ver
 * {@code ChatFeedback.tag}); aquí solo se reconocen esas líneas al recibirlas y se resumen en pantalla
 * un rato. Por eso {@link #push} es privado: nadie puede escribir en este panel directamente — si una
 * línea no pasó por {@code ChatFeedback}, no existe para este panel.</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class CombatLogOverlay {

	//Encogido tras la primera partida real ("interfaz saturada"): menos eventos, menos vida y menos
	//ancho — el panel es un vistazo, no una segunda ventana de chat compitiendo con la de verdad.
	private static final int MAX_ENTRIES = 4;
	private static final long LIFE_MS = 8_000;
	//Mismo suelo que los otros dos paneles (ver TurnHudOverlay): menos y el texto pisa la cantonera.
	private static final int PADDING = 7;
	private static final int LINE_HEIGHT = 9;
	private static final int CONTENT_WIDTH = 170;

	private record Entry(Component line, long bornAt) {}

	private static final ArrayDeque<Entry> entries = new ArrayDeque<>();

	@SubscribeEvent
	public static void registerOverlays(RegisterGuiOverlaysEvent event) {
		event.registerAboveAll("dndsheets_combat_log", (gui, guiGraphics, partialTick, width, height) -> render(guiGraphics, width, height));
	}

	//Bus FORGE (eventos de juego), separado del bus MOD de la clase (registro de overlays).
	@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, value = Dist.CLIENT)
	public static class Chat {
		@SubscribeEvent
		public static void onSystemChat(ClientChatReceivedEvent.System event) {
			if (!event.isOverlay() && hasModTag(event.getMessage())) push(event.getMessage());
		}
	}

	private static void push(Component line) {
		//El resumen semántico viaja DENTRO de la propia línea de chat, como insertion invisible (ver
		//ChatFeedback.withSummary): si está, el panel enseña ESO ("Mago ▶ Creeper · 17 ✓ 6") y el
		//desglose queda solo en el chat. Sin resumen (líneas raras: caídas, reanimaciones), se cae a la
		//línea completa recortada — mejor larga que ausente.
		String summary = findSummary(line);
		entries.addLast(new Entry(summary != null ? Component.literal(summary) : line, System.currentTimeMillis()));
		while (entries.size() > MAX_ENTRIES) entries.removeFirst();
	}

	private static String findSummary(Component component) {
		String insertion = component.getStyle().getInsertion();
		if (insertion != null && insertion.startsWith(net.hawthorn.dndsheets.ChatFeedback.SUMMARY_PREFIX)) {
			return insertion.substring(net.hawthorn.dndsheets.ChatFeedback.SUMMARY_PREFIX.length());
		}
		for (Component sibling : component.getSiblings()) {
			String found = findSummary(sibling);
			if (found != null) return found;
		}
		return null;
	}

	//La etiqueta es el primer trozo de toda línea de ChatFeedback, pero puede venir anidada dentro del
	//árbol del Component, así que se busca en profundidad y no solo en la raíz.
	private static boolean hasModTag(Component component) {
		if (component.getContents() instanceof TranslatableContents translatable
			&& translatable.getKey().startsWith("chat.dndsheets.tag.")) return true;
		for (Component sibling : component.getSiblings()) {
			if (hasModTag(sibling)) return true;
		}
		return false;
	}

	private static void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
		long now = System.currentTimeMillis();
		while (!entries.isEmpty() && now - entries.peekFirst().bornAt() > LIFE_MS) entries.removeFirst();
		if (entries.isEmpty()) return;

		Font font = Minecraft.getInstance().font;

		//Una línea por evento, recortada y NUNCA envuelta: este panel es el resumen, el desglose entero
		//ya está en el chat (pedido explícito del dueño: detalle solo en el chat). substrByWidth respeta
		//los estilos del Component, así que el recorte conserva los colores de ChatFeedback.
		List<FormattedCharSequence> wrapped = new ArrayList<>(entries.size());
		int widest = 0;
		int ellipsisWidth = font.width("…");
		for (Entry entry : entries) {
			FormattedCharSequence line;
			if (font.width(entry.line()) <= CONTENT_WIDTH) {
				line = entry.line().getVisualOrderText();
			} else {
				line = net.minecraft.locale.Language.getInstance().getVisualOrder(net.minecraft.network.chat.FormattedText.composite(
					font.substrByWidth(entry.line(), CONTENT_WIDTH - ellipsisWidth),
					net.minecraft.network.chat.FormattedText.of("…")));
			}
			wrapped.add(line);
			widest = Math.max(widest, font.width(line));
		}

		//Abajo a la derecha, por encima de la hotbar: la esquina que los otros dos paneles no ocupan y
		//donde no tapa ni el chat (abajo-izquierda) ni el tablero de turnos (arriba-derecha).
		int right = screenWidth - 8;
		int left = right - widest - 2 * PADDING;
		int bottom = screenHeight - 45;
		int top = bottom - wrapped.size() * LINE_HEIGHT - 2 * PADDING + 4;

		GuiStyle.panel(guiGraphics, left, top, right, bottom);

		int y = top + PADDING - 2;
		for (FormattedCharSequence piece : wrapped) {
			guiGraphics.drawString(font, piece, left + PADDING, y, GuiStyle.SUBTITLE_COLOR);
			y += LINE_HEIGHT;
		}
	}
}
