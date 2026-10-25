package net.hawthorn.dndsheets.client;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.client.gui.GuiStyle;
import net.hawthorn.dndsheets.client.gui.Icons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>Active-effects panel: always visible (no need to open the Grimoire, the sheet, or be in combat)
 * showing what Minecraft doesn't display natively — conditions, temporary HP, concentration, pending
 * inspiration/smite/advantage, spell slots, and gold. Read directly from the client sheet
 * ({@link SheetLoader#getClientSheet()}), the same one already kept in sync by every command that
 * touches it (slots, rests, conditions, gold...), so it needs no network message of its own.</p>
 *
 * <p>It's the persistent counterpart to {@link TurnHudOverlay}, which only exists while combat is
 * active: half of this data (conditions, concentration) matters outside of turns too, so it lives in
 * its own panel, in the opposite corner of the screen, with the same tome-like look (see
 * {@link GuiStyle}) so the two pieces read as one interface instead of two separate mods.</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ResourceHudOverlay {

	//PADDING doesn't go below 7: any less and the text sinks under the corner's brass brace (see
	//GuiStyle — same limit as TurnHudOverlay). Everything else, tightened per request.
	private static final int PADDING = 7;
	private static final int ROW_HEIGHT = 9;
	private static final int MIN_WIDTH = 95;

	@SubscribeEvent
	public static void registerOverlays(RegisterGuiOverlaysEvent event) {
		event.registerAboveAll("dndsheets_resources", (gui, guiGraphics, partialTick, width, height) -> render(guiGraphics));
	}

	//The HUD lines, already assembled, and the sheet version they were assembled from. This render runs
	//every FRAME with nothing needing to be opened, so it's the mod's most-frequently-executed code;
	//rebuilding these strings at 120 fps was ~2,500 allocations per second for text that only changes
	//when the sheet changes. Each line carries its own color: they don't all mean the same thing (a
	//condition isn't a resource), so a single color for the whole panel would make them indistinguishable
	//at a glance.
	//A line is text, or (when icons is not empty) a row of condition glyphs drawn instead of text.
	private record Line(String text, int color, java.util.List<ResourceLocation> icons, ResourceLocation rowIcon) {
		Line(String text, int color) {
			this(text, color, java.util.List.of(), null);
		}

		Line(String text, int color, ResourceLocation rowIcon) {
			this(text, color, java.util.List.of(), rowIcon);
		}

		Line(String text, int color, java.util.List<ResourceLocation> icons) {
			this(text, color, icons, null);
		}
	}

	private static final int ROW_ICON = 10;
	private static final int ROW_ICON_GAP = 3;

	private static final int ICON_SIZE = 14;
	private static final int ICON_GAP = 2;
	private static final int ICON_ROW_HEIGHT = ICON_SIZE + 2;

	private static int cachedVersion = -1;
	private static Line[] cachedLines = new Line[0];

	private static void rebuild(JsonObject sheet) {
		java.util.List<Line> lines = new java.util.ArrayList<>();

		int spellSlotsMax = sheet.has("spellSlotsMax") ? sheet.get("spellSlotsMax").getAsInt() : 0;
		if (spellSlotsMax > 0) {
			int slots = sheet.has("spellSlotsCurrent") ? sheet.get("spellSlotsCurrent").getAsInt() : 0;
			lines.add(new Line(Component.translatable("hud.dndsheets.spell_slots", slots, spellSlotsMax).getString(), GuiStyle.INFO_COLOR, Icons.stat("spell")));
		}

		//Temporary HP: a buffer Minecraft doesn't represent (its health bar is only the real HP), so
		//without this a player with temporary HP had no way to know how much buffer they had left until a
		//hit started eating into it.
		int temporaryHp = sheet.has("temporaryHp") ? sheet.get("temporaryHp").getAsInt() : 0;
		if (temporaryHp > 0) lines.add(new Line(Component.translatable("hud.dndsheets.temp_hp", temporaryHp).getString(), GuiStyle.GOOD_COLOR, Icons.stat("hp")));

		//Active conditions, in red and above everything else. These used to live ONLY in the DM Panel, so
		//a paralyzed player had no way to know it: their clicks simply stopped doing anything, and that
		//reads as the mod being broken rather than as the rule it actually is. Half a dozen rules-engine
		//checks depend on conditions and none of them were visible from the sufferer's side.
		String conditions = activeConditionLabels(sheet);
		if (!conditions.isEmpty()) {
			java.util.List<ResourceLocation> glyphs = conditionIcons(sheet);
			if (!glyphs.isEmpty()) lines.add(new Line("", 0, glyphs));
			lines.add(new Line(conditions, GuiStyle.BAD_COLOR));
		}

		//The "things you're carrying" that decide your next roll. All of it used to live server-side: you'd
		//receive Bardic Inspiration and not know it, prime a Smite and not know if it was still armed three
		//turns later, and concentration — one of the most-checked things at a table — only existed as a
		//chat line that scrolls away. A modifier you can't see isn't something you can play around; you only
		//find out about it after the fact, in the result.
		String held = heldEffects(sheet);
		if (!held.isEmpty()) lines.add(new Line(held, 0xFFFFD9A0, Icons.stat("concentration")));

		if (sheet.has("gold")) lines.add(new Line(Component.translatable("hud.dndsheets.gold", sheet.get("gold").getAsInt()).getString(), 0xFFFFD700, Icons.stat("gold")));

		cachedLines = lines.toArray(new Line[0]);
	}

	private static void render(GuiGraphics guiGraphics) {
		//Under any open screen but chat the sheet/menu panel covers it and half the text shows through: hide it.
		var screen = Minecraft.getInstance().screen;
		if (screen != null && !(screen instanceof net.minecraft.client.gui.screens.ChatScreen)) return;
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null) return;

		int version = SheetLoader.clientSheetVersion();
		if (version != cachedVersion) {
			rebuild(sheet);
			cachedVersion = version;
		}
		if (cachedLines.length == 0) return; //Nothing active: better no panel than an empty one taking up a corner.

		Font font = Minecraft.getInstance().font;
		int contentWidth = MIN_WIDTH;
		int rowsHeight = 0;
		for (Line line : cachedLines) {
			if (line.icons().isEmpty()) {
				contentWidth = Math.max(contentWidth, font.width(line.text()) + (line.rowIcon() != null ? ROW_ICON + ROW_ICON_GAP : 0));
				rowsHeight += ROW_HEIGHT;
			} else {
				contentWidth = Math.max(contentWidth, line.icons().size() * (ICON_SIZE + ICON_GAP) - ICON_GAP);
				rowsHeight += ICON_ROW_HEIGHT;
			}
		}

		int top = 8;
		int left = 8;
		int right = left + contentWidth + 2 * PADDING;
		int bottom = top + 2 * PADDING - 4 + rowsHeight;

		GuiStyle.panel(guiGraphics, left, top, right, bottom);

		int textX = left + PADDING;
		int y = top + PADDING - 2;
		for (Line line : cachedLines) {
			if (line.icons().isEmpty()) {
				int textStart = textX;
				if (line.rowIcon() != null) {
					Icons.draw(guiGraphics, line.rowIcon(), textX, y - 1, ROW_ICON);
					textStart += ROW_ICON + ROW_ICON_GAP;
				}
				guiGraphics.drawString(font, line.text(), textStart, y, line.color());
				y += ROW_HEIGHT;
			} else {
				int x = textX;
				for (ResourceLocation glyph : line.icons()) {
					Icons.draw(guiGraphics, glyph, x, y, ICON_SIZE);
					x += ICON_SIZE + ICON_GAP;
				}
				y += ICON_ROW_HEIGHT;
			}
		}
	}

	/**
	 * <p>Labels of the active conditions, comma-separated. The "@id" that each one carries its source
	 * with (see {@code Combatant.formatEntry}) is stripped: what matters to the one suffering it is that
	 * they're frightened, not the entity number that frightened them.</p>
	 */
	private static String activeConditionLabels(JsonObject sheet) {
		if (!sheet.has("conditions")) return "";
		StringBuilder labels = new StringBuilder();
		for (var element : sheet.getAsJsonArray("conditions")) {
			String entry = element.getAsString();
			int at = entry.indexOf('@');
			if (labels.length() > 0) labels.append(", ");
			labels.append(at < 0 ? entry : entry.substring(0, at));
		}
		return labels.length() == 0 ? "" : Component.translatable("hud.dndsheets.conditions", labels.toString()).getString();
	}

	/** One glyph per active condition that has one; a condition without a glyph still shows in the text line. */
	private static java.util.List<ResourceLocation> conditionIcons(JsonObject sheet) {
		java.util.List<ResourceLocation> glyphs = new java.util.ArrayList<>();
		for (var element : sheet.getAsJsonArray("conditions")) {
			String entry = element.getAsString();
			int at = entry.indexOf('@');
			ResourceLocation glyph = Icons.condition(at < 0 ? entry : entry.substring(0, at));
			if (glyph != null && !glyphs.contains(glyph)) glyphs.add(glyph);
		}
		return glyphs;
	}

	/** The "things carried" that change the next roll: concentration, inspiration die, armed smite, pending advantage. */
	private static String heldEffects(JsonObject sheet) {
		StringBuilder held = new StringBuilder();
		if (sheet.has("concentratingOn")) append(held, Component.translatable("hud.dndsheets.concentrating", net.hawthorn.dndsheets.ContentNames.of(sheet.get("concentratingOn").getAsString())).getString());
		if (sheet.has("bardicInspiration")) append(held, Component.translatable("hud.dndsheets.inspiration", sheet.get("bardicInspiration").getAsInt()).getString());
		if (sheet.has("smitePending")) append(held, Component.translatable("hud.dndsheets.smite_armed").getString());
		//"normal" is the resting value, not a pending advantage: showing it would be a permanent line that
		//says nothing and would end up ignored along with the ones that actually matter.
		if (sheet.has("nextAttackAdvantage")) {
			String advantage = sheet.get("nextAttackAdvantage").getAsString();
			if ("advantage".equals(advantage)) append(held, Component.translatable("hud.dndsheets.advantage").getString());
			else if ("disadvantage".equals(advantage)) append(held, Component.translatable("hud.dndsheets.disadvantage").getString());
		}
		return held.toString();
	}

	private static void append(StringBuilder to, String text) {
		if (to.length() > 0) to.append(" · ");
		to.append(text);
	}
}
