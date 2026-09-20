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
 * <p>Ephemeral summary of the most recent combat events (rolls, attacks, spells, death saves), bottom
 * right, with the same tome-like look as the rest of the HUD (see {@link GuiStyle}). <b>One line per
 * event, trimmed with an ellipsis, never wrapped</b>: the full detail (dice breakdown, against which AC,
 * damage type) lives ONLY in chat — having it in full in both places meant reading the same thing twice,
 * and this panel exists for a glance, not for reading.</p>
 *
 * <p>No new network message and no changes to any manager: EVERY combat line the mod produces already
 * reaches the client as chat and already starts with a translatable {@code chat.dndsheets.tag.*} tag
 * (see {@code ChatFeedback.tag}); this class only recognizes those lines as they arrive and summarizes
 * them on screen for a while. That's why {@link #push} is private: nobody can write to this panel
 * directly — if a line didn't go through {@code ChatFeedback}, it doesn't exist for this panel.</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class CombatLogOverlay {

	//Shrunk after the first real session ("interface felt cluttered"): fewer events, shorter lifetime,
	//and less width — the panel is meant for a glance, not a second chat window competing with the real one.
	private static final int MAX_ENTRIES = 4;
	private static final long LIFE_MS = 8_000;
	//Same floor as the other two panels (see TurnHudOverlay): any less and the text runs into the corner brace.
	private static final int PADDING = 7;
	private static final int LINE_HEIGHT = 9;
	private static final int CONTENT_WIDTH = 170;

	private record Entry(Component line, long bornAt) {}

	private static final ArrayDeque<Entry> entries = new ArrayDeque<>();

	@SubscribeEvent
	public static void registerOverlays(RegisterGuiOverlaysEvent event) {
		event.registerAboveAll("dndsheets_combat_log", (gui, guiGraphics, partialTick, width, height) -> render(guiGraphics, width, height));
	}

	//FORGE bus (game events), separate from the class's MOD bus (overlay registration).
	@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, value = Dist.CLIENT)
	public static class Chat {
		@SubscribeEvent
		public static void onSystemChat(ClientChatReceivedEvent.System event) {
			if (!event.isOverlay() && hasModTag(event.getMessage())) push(event.getMessage());
		}
	}

	private static void push(Component line) {
		//The semantic summary travels INSIDE the chat line itself, as an invisible insertion (see
		//ChatFeedback.withSummary): if present, the panel shows THAT ("Wizard ▶ Creeper · 17 ✓ 6") and the
		//breakdown stays chat-only. Without a summary (rare lines: falls, revivals), it falls back to the
		//full trimmed line — better long than missing.
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

	//The tag is the first chunk of every ChatFeedback line, but it can arrive nested inside the
	//Component tree, so the search goes depth-first instead of just checking the root.
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

		//One line per event, trimmed and NEVER wrapped: this panel is the summary, the full breakdown is
		//already in chat (explicit owner request: detail stays chat-only). substrByWidth respects the
		//Component's styles, so trimming preserves ChatFeedback's colors.
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

		//Bottom right, above the hotbar: the corner the other two panels don't occupy, and where it
		//doesn't cover either the chat (bottom-left) or the turn tracker (top-right).
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
