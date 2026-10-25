package net.hawthorn.dndsheets.client;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.client.gui.GuiStyle;
import net.hawthorn.dndsheets.client.gui.Icons;
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
 * <p>Turn-mode HUD: not just whose turn it is (that already existed), but the entire initiative tracker —
 * order, who has already acted, who's down, what conditions each combatant is carrying — with the same
 * bound-tome look as the rest of the mod's interface (see {@link GuiStyle}), instead of floating text
 * with no panel.</p>
 *
 * <p>This information used to exist, but only in chat: every roll, every condition applied, every
 * "it's X's turn" was one more text line in a list that keeps growing throughout combat. A new player has
 * no way to know, without scrolling up, who's still standing or what's happening to their character right
 * now. This panel doesn't replace chat — the messages still appear — but it gives the current state at a
 * glance, the way a real table is read.</p>
 *
 * <p>Movement and the player's own action economy are computed client-side against the turn origin
 * already sent by {@link net.hawthorn.dndsheets.network.TurnStateMessage}, without asking the server for
 * anything more; the rest of the tracker (names, conditions, who acted) does travel in that same message
 * because it's information about OTHER combatants that the client has no way to compute on its own.</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class TurnHudOverlay {
	private static final int DEFAULT_SPEED_FEET = 30;
	//speedBlocksFromClientSheet runs every frame while it's the local player's turn: the Pattern is
	//cached instead of recompiled every frame.
	private static final Pattern SPEED_FEET_PATTERN = Pattern.compile("\\d+");

	//Shrunk on request: the panel was taking up too much screen. PADDING doesn't go below 7 even as
	//everything else is tightened — any less and the text sinks under the corner's brass brace (see
	//GuiStyle: 3px arm + 2px margin = 5px minimum to avoid overlapping it).
	private static final int PANEL_WIDTH = 176;
	private static final int PADDING = 7;
	private static final int ROW_HEIGHT = 10;
	//Used to be 10: with a large encounter the panel became huge before the centered window (see
	//render) kicked in to trim it. At 6, most combats fit entirely and a large one gets trimmed sooner.
	private static final int MAX_ROSTER_ROWS = 6;

	private static final int MOVEMENT_COLOR = 0xFF6FD1C6;
	private static final int MOVEMENT_TRACK_COLOR = 0xFF3A342A;
	private static final int GOOD_COLOR = 0xFF6FCB6F;
	private static final int ENEMY_COLOR = 0xFFCC7A6E;
	private static final int CONDITION_COLOR = 0xFFB08A5A;
	private static final int ICON_SIZE = 8;
	private static final int CHIP_HEIGHT = 14;
	private static final int CHIP_ICON = 12;
	private static final int MAX_ROW_ICONS = 3;

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

		//Window centered on whoever has the turn: with a swarm or a fight against many enemies, the row
		//cap alone isn't enough — without this, if the active turn fell past row 10 the panel would leave
		//it out of view with no warning, and the HUD would look "stuck" on a combatant whose turn already
		//passed.
		int total = roster.size();
		boolean truncated = total > MAX_ROSTER_ROWS;
		int windowCap = truncated ? MAX_ROSTER_ROWS - 1 : MAX_ROSTER_ROWS; //One row fewer if the "+N hidden" indicator is needed.
		int currentIdx = indexOfCurrent(roster);
		int start = truncated ? Math.max(0, Math.min(currentIdx - windowCap / 2, total - windowCap)) : 0;
		int end = truncated ? start + windowCap : total;
		int hidden = total - (end - start);

		//Two passes: first the required panel size is measured, then the background is drawn, then the
		//content on top of it — GuiStyle.panel needs to know the bottom edge BEFORE anything is painted.
		int top = 8;
		int left = screenWidth - 8 - PANEL_WIDTH;
		int right = screenWidth - 8;
		int contentTop = top + PADDING;

		int height = ROW_HEIGHT + 4 + (end - start) * ROW_HEIGHT + (truncated ? ROW_HEIGHT : 0);
		if (myRow != null) height += 4 + CHIP_HEIGHT + ROW_HEIGHT + 3; //Action / bonus / reaction chips, then movement.
		int bottom = contentTop + height + PADDING - 4;

		GuiStyle.panel(guiGraphics, left, top, right, bottom);

		int textLeft = left + PADDING;
		int textRight = right - PADDING;
		int y = contentTop;

		guiGraphics.drawString(font, GuiStyle.title(Component.translatable("hud.dndsheets.round", TurnHudState.round())), textLeft, y, GuiStyle.ACCENT_COLOR);
		y += ROW_HEIGHT;
		GuiStyle.rule(guiGraphics, textLeft, textRight, y);
		y += 4;

		for (int i = start; i < end; i++) {
			drawRosterRow(guiGraphics, font, roster.get(i), textLeft, textRight, y,
				roster.get(i).entityId() == TurnHudState.currentEntityId());
			y += ROW_HEIGHT;
		}
		if (truncated) {
			Component more = Component.translatable(hidden == 1 ? "hud.dndsheets.hidden_one" : "hud.dndsheets.hidden_many", hidden);
			guiGraphics.drawString(font, more, textLeft, y, GuiStyle.MUTED_COLOR);
			y += ROW_HEIGHT;
		}

		if (myRow != null) {
			GuiStyle.rule(guiGraphics, textLeft, textRight, y);
			y += 4;
			drawEconomy(guiGraphics, font, myRow, minecraft, textLeft, textRight, y);
		}
	}

	//0 if not found (combat just started, first packet): better to center the window at the start of the
	//order than to crash on an invalid index.
	private static int indexOfCurrent(List<TurnStateMessage.RosterRow> roster) {
		int currentEntityId = TurnHudState.currentEntityId();
		for (int i = 0; i < roster.size(); i++) {
			if (roster.get(i).entityId() == currentEntityId) return i;
		}
		return 0;
	}

	//An initiative row: "▶ Name" if it's their turn now, struck through and dimmed if they're down, and a
	//suffix on the right with the most urgent thing to know about that row — conditions if any, otherwise
	//a discreet checkmark for "already acted this round". The two things deliberately share the same slot:
	//showing both would double the panel's height per combatant, and the condition is always the more
	//important of the two.
	private static void drawRosterRow(GuiGraphics guiGraphics, Font font, TurnStateMessage.RosterRow row,
									   int left, int right, int y, boolean isCurrent) {
		if (isCurrent) {
			//Whose turn it is should read at a glance: a brass bar and a faint fill behind the row, not just a marker.
			guiGraphics.fill(left - 3, y - 1, right + 3, y + ROW_HEIGHT - 1, 0x33C9A227);
			guiGraphics.fill(left - 3, y - 1, left - 1, y + ROW_HEIGHT - 1, GuiStyle.ACCENT_COLOR);
		}
		String marker = isCurrent ? "▶ " : "   ";
		int nameColor = row.defeated() ? GuiStyle.MUTED_COLOR
			: isCurrent ? GuiStyle.TITLE_COLOR
			: row.isMonster() ? ENEMY_COLOR : GuiStyle.SUBTITLE_COLOR;

		//1px health bar under the name: the info that at a real table comes from the miniature itself
		//("how's that ogre doing?") and that here used to only be obtainable by asking in chat. maxHp 0 =
		//combatant outside the rules engine or unloaded: no bar, same as before.
		if (row.maxHp() > 0 && !row.defeated()) {
			int barY = y + ROW_HEIGHT - 2;
			double fraction = Math.min(1.0, Math.max(0.0, row.currentHp() / (double) row.maxHp()));
			int fillColor = row.currentHp() * 2 >= row.maxHp() ? GOOD_COLOR : ENEMY_COLOR;
			guiGraphics.fill(left, barY, right, barY + 1, MOVEMENT_TRACK_COLOR);
			guiGraphics.fill(left, barY, left + (int) Math.round((right - left) * fraction), barY + 1, fillColor);
		}

		net.minecraft.network.chat.MutableComponent name = Component.literal(marker).append(net.hawthorn.dndsheets.ContentNames.of(row.name()));
		if (row.defeated()) name = name.withStyle(ChatFormatting.STRIKETHROUGH);
		guiGraphics.drawString(font, name, left, y, nameColor);

		String suffix = null;
		int suffixColor = CONDITION_COLOR;
		if (row.defeated()) {
			suffix = null; //The strikethrough already says it; repeating "defeated" next to it would be noise.
		} else if (!row.conditions().isEmpty()) {
			suffix = row.conditions().size() == 1 ? row.conditions().get(0)
				: row.conditions().get(0) + " +" + (row.conditions().size() - 1);
		} else if (row.acted() && !isCurrent) {
			suffix = "✓";
			suffixColor = GuiStyle.MUTED_COLOR;
		}
		if (!row.defeated() && !row.conditions().isEmpty()) {
			//Glyphs instead of words for the conditions that have one (up to three, then "+N"): 8px each, so a
			//row of them is shorter than one word and the name never gets pushed.
			int x = right;
			int shown = 0;
			for (String label : row.conditions()) {
				net.minecraft.resources.ResourceLocation glyph = Icons.condition(label);
				if (glyph == null || shown == MAX_ROW_ICONS) continue;
				x -= ICON_SIZE;
				Icons.draw(guiGraphics, glyph, x, y, ICON_SIZE);
				x -= 1;
				shown++;
			}
			int hidden = row.conditions().size() - shown;
			if (shown == 0) {
				guiGraphics.drawString(font, suffix, right - font.width(suffix), y, suffixColor);
			} else if (hidden > 0) {
				String more = "+" + hidden;
				guiGraphics.drawString(font, more, x - font.width(more), y, suffixColor);
			}
		} else if (suffix != null) {
			guiGraphics.drawString(font, suffix, right - font.width(suffix), y, suffixColor);
		}
	}

	//The player's own action/reaction/movement state: the only thing on this HUD that actually matters
	//for decision-making (the rest of the tracker is information, this is "what I still have left to
	//spend"). Shown whenever the player has a slot in the order, not just on their turn — a reaction can
	//be spent outside of turn.
	private static void drawEconomy(GuiGraphics guiGraphics, Font font, TurnStateMessage.RosterRow myRow,
									 Minecraft minecraft, int left, int right, int y) {
		boolean myTurn = myRow.entityId() == TurnHudState.currentEntityId();
		boolean actionAvailable = myTurn && !TurnHudState.actionUsed();

		boolean bonusActionAvailable = myTurn && !myRow.bonusActionUsed();

		//Three chips instead of three text lines: the glyph is lit while that part of the turn can still be spent and
		//dimmed once it is used (or, for action and bonus action, when it is not your turn).
		int chipW = (right - left) / 3;
		drawChip(guiGraphics, font, Icons.stat("attack"), Component.translatable("hud.dndsheets.action"), actionAvailable, left, y, chipW);
		drawChip(guiGraphics, font, Icons.byPath("action/surge"), Component.translatable("hud.dndsheets.bonus_action"), bonusActionAvailable, left + chipW, y, chipW);
		drawChip(guiGraphics, font, Icons.byPath("action/ready"), Component.translatable("hud.dndsheets.reaction"), !myRow.reactionUsed(), left + 2 * chipW, y, chipW);
		y += CHIP_HEIGHT;

		double speedBlocks = speedBlocksFromClientSheet();
		double distanceMoved = minecraft.player.position().distanceTo(new Vec3(TurnHudState.originX(), TurnHudState.originY(), TurnHudState.originZ()));
		double remaining = Math.max(0, speedBlocks - distanceMoved);
		Component moveText = Component.translatable("hud.dndsheets.movement", Math.round(remaining), Math.round(speedBlocks));
		Icons.draw(guiGraphics, Icons.stat("speed"), left, y - 1, 10);
		guiGraphics.drawString(font, moveText, left + 13, y, MOVEMENT_COLOR);

		int barWidth = right - left;
		int barY = y + 9;
		double fraction = speedBlocks <= 0 ? 0 : Math.min(1.0, remaining / speedBlocks);
		guiGraphics.fill(left, barY, right, barY + 2, MOVEMENT_TRACK_COLOR);
		guiGraphics.fill(left, barY, left + (int) Math.round(barWidth * fraction), barY + 2, MOVEMENT_COLOR);
	}

	private static void drawChip(GuiGraphics guiGraphics, Font font, net.minecraft.resources.ResourceLocation icon, Component label,
								  boolean available, int x, int y, int width) {
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		float shade = available ? 1.0f : 0.35f;
		com.mojang.blaze3d.systems.RenderSystem.setShaderColor(shade, shade, shade, 1.0f);
		Icons.draw(guiGraphics, icon, x, y, CHIP_ICON);
		com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		guiGraphics.drawString(font, font.plainSubstrByWidth(label.getString(), width - CHIP_ICON - 3), x + CHIP_ICON + 3, y + 2,
			available ? GOOD_COLOR : GuiStyle.MUTED_COLOR);
	}

	//Same conversion as MovementAnchorTracker.speedBlocksFor on the server, but read from the sheet already
	//synced to the client (SheetLoader.getClientSheet()) — the HUD doesn't need to ask the server for anything new.
	private static double speedBlocksFromClientSheet() {
		JsonObject sheet = SheetLoader.getClientSheet();
		int feet = DEFAULT_SPEED_FEET;
		if (sheet != null && sheet.has("speed")) {
			Matcher matcher = SPEED_FEET_PATTERN.matcher(sheet.get("speed").getAsString());
			if (matcher.find()) {
				try { feet = Integer.parseInt(matcher.group()); } catch (NumberFormatException ignored) {}
			}
		}
		return feet / (double) TurnHudState.feetPerBlock();
	}
}
