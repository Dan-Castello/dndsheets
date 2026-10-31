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
 * the opposite corner of the screen, as one card per effect styled like the vanilla potion effects in
 * the inventory, so it reads as part of the game's own status display.</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ResourceHudOverlay {

	//One card per effect, drawn on the vanilla wide effect background (inventory.png, 0,166 120x32) so it
	//reads like the potion effects in the inventory — and follows whatever resource pack re-skins those.
	private static final ResourceLocation EFFECT_BACKGROUND = new ResourceLocation("textures/gui/container/inventory.png");
	private static final int CARD_WIDTH = 120;
	private static final int CARD_HEIGHT = 32;
	private static final int TEXT_WIDTH = CARD_WIDTH - 28 - 4;
	private static final int SUBTITLE_COLOR = 0xFF7F7F7F;
	private static final int ICON_FRAME = 24;
	//Everything drawn 30% smaller than vanilla's effect widgets: full size took too much of the screen.
	private static final float SCALE = 0.7f;

	@SubscribeEvent
	public static void registerOverlays(RegisterGuiOverlaysEvent event) {
		event.registerAboveAll("dndsheets_resources", (gui, guiGraphics, partialTick, width, height) -> render(guiGraphics));
	}

	//The HUD cards, already assembled, and the sheet version they were assembled from. This render runs
	//every FRAME with nothing needing to be opened, so it's the mod's most-frequently-executed code;
	//rebuilding these strings at 120 fps was ~2,500 allocations per second for text that only changes
	//when the sheet changes. Each card carries its own title color: they don't all mean the same thing (a
	//condition isn't a resource), so a single color would make them indistinguishable at a glance.
	//subtitle is null for cards with nothing to show under the name (a condition has no timer).
	private record Card(ResourceLocation icon, String title, String subtitle, int titleColor) {}

	private static int cachedVersion = -1;
	private static Card[] cachedCards = new Card[0];

	private static String tr(String key) {
		return Component.translatable(key).getString();
	}

	private static void rebuild(JsonObject sheet) {
		java.util.List<Card> cards = new java.util.ArrayList<>();

		int spellSlotsMax = sheet.has("spellSlotsMax") ? sheet.get("spellSlotsMax").getAsInt() : 0;
		if (spellSlotsMax > 0) {
			int slots = sheet.has("spellSlotsCurrent") ? sheet.get("spellSlotsCurrent").getAsInt() : 0;
			cards.add(new Card(Icons.stat("spell"), tr("hud.dndsheets.card.spell_slots"), slots + "/" + spellSlotsMax, GuiStyle.INFO_COLOR));
		}

		//Temporary HP: a buffer Minecraft doesn't represent (its health bar is only the real HP), so
		//without this a player with temporary HP had no way to know how much buffer they had left until a
		//hit started eating into it.
		int temporaryHp = sheet.has("temporaryHp") ? sheet.get("temporaryHp").getAsInt() : 0;
		if (temporaryHp > 0) cards.add(new Card(Icons.stat("hp"), tr("hud.dndsheets.card.temp_hp"), String.valueOf(temporaryHp), GuiStyle.GOOD_COLOR));

		//Active conditions, one card each, in red. These used to live ONLY in the DM Panel, so a paralyzed
		//player had no way to know it: their clicks simply stopped doing anything, and that reads as the mod
		//being broken rather than as the rule it actually is. The "@id" each one carries its source with (see
		//Combatant.formatEntry) is stripped: what matters is being frightened, not the entity number.
		if (sheet.has("conditions")) {
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (var element : sheet.getAsJsonArray("conditions")) {
				String entry = element.getAsString();
				int at = entry.indexOf('@');
				String label = at < 0 ? entry : entry.substring(0, at);
				if (label.isEmpty() || !seen.add(label)) continue;
				String title = net.hawthorn.dndsheets.client.gui.OptionLabels.of(label);
				cards.add(new Card(Icons.condition(label), title, null, GuiStyle.BAD_COLOR));
			}
		}

		//The "things you're carrying" that decide your next roll. All of it used to live server-side: you'd
		//receive Bardic Inspiration and not know it, prime a Smite and not know if it was still armed three
		//turns later, and concentration — one of the most-checked things at a table — only existed as a
		//chat line that scrolls away. A modifier you can't see isn't something you can play around.
		ResourceLocation held = Icons.stat("concentration");
		if (sheet.has("concentratingOn")) cards.add(new Card(held, tr("hud.dndsheets.card.concentrating"), net.hawthorn.dndsheets.ContentNames.of(sheet.get("concentratingOn").getAsString()).getString(), 0xFFFFD9A0));
		if (sheet.has("bardicInspiration")) cards.add(new Card(held, tr("hud.dndsheets.card.inspiration"), "+" + sheet.get("bardicInspiration").getAsInt(), 0xFFFFD9A0));
		if (sheet.has("smitePending")) cards.add(new Card(held, tr("hud.dndsheets.smite_armed"), null, 0xFFFFD9A0));
		//"normal" is the resting value, not a pending advantage: a permanent card that says nothing would
		//end up ignored along with the ones that actually matter.
		if (sheet.has("nextAttackAdvantage")) {
			String advantage = sheet.get("nextAttackAdvantage").getAsString();
			if ("advantage".equals(advantage)) cards.add(new Card(held, tr("hud.dndsheets.advantage"), null, 0xFFFFD9A0));
			else if ("disadvantage".equals(advantage)) cards.add(new Card(held, tr("hud.dndsheets.disadvantage"), null, 0xFFFFD9A0));
		}

		if (sheet.has("gold")) cards.add(new Card(Icons.stat("gold"), tr("hud.dndsheets.card.gold"), String.valueOf(sheet.get("gold").getAsInt()), 0xFFFFD700));

		cachedCards = cards.toArray(new Card[0]);
	}

	//Rebuilds the cached cards if the sheet changed; false when there's no sheet or nothing to show.
	private static boolean refresh() {
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null) return false;
		int version = SheetLoader.clientSheetVersion();
		if (version != cachedVersion) {
			rebuild(sheet);
			cachedVersion = version;
		}
		return cachedCards.length > 0; //Nothing active: better nothing than an empty strip taking up a corner.
	}

	//In play the HUD is a row of small icons, like the vanilla effect icons in the top-right corner; holding
	//the player-list key (Tab) unfolds them into the full cards, and the inventory shows them too (see Inventory).
	private static void render(GuiGraphics guiGraphics) {
		//Under any open screen but chat the sheet/menu panel covers it and half the text shows through: hide it.
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen != null && !(minecraft.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) return;
		//F1 hides the GUI and F3's text fills this corner: custom overlays have to check both themselves.
		if (minecraft.options.hideGui || minecraft.options.renderDebug) return;
		if (!refresh()) return;
		if (minecraft.options.keyPlayerList.isDown()) drawCards(guiGraphics, 8, 8);
		else drawIcons(guiGraphics, 8, 8, true);
	}

	//Vanilla HUD effect icon: 24x24 frame from inventory.png (141,166) with the 18px icon inset. Cards with a
	//short value (slots, temp HP, gold...) show it in the corner like an item count, so the numbers that
	//matter mid-fight are still readable without unfolding anything.
	private static void drawIcons(GuiGraphics guiGraphics, int screenX, int screenY, boolean horizontal) {
		Font font = Minecraft.getInstance().font;
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(screenX, screenY, 0);
		guiGraphics.pose().scale(SCALE, SCALE, 1);
		int x = 0;
		int y = 0;
		for (Card card : cachedCards) {
			guiGraphics.blit(EFFECT_BACKGROUND, x, y, 141, 166, ICON_FRAME, ICON_FRAME);
			if (card.icon() != null) Icons.draw(guiGraphics, card.icon(), x + 3, y + 3, 18);
			if (card.subtitle() != null && font.width(card.subtitle()) <= ICON_FRAME) {
				//A dark backing under the value: drawn straight over the glyph, "0/2" was unreadable.
				int badgeX = x + ICON_FRAME - font.width(card.subtitle()) - 1;
				guiGraphics.fill(badgeX - 1, y + ICON_FRAME - 9, x + ICON_FRAME, y + ICON_FRAME, 0xC0000000);
				guiGraphics.drawString(font, card.subtitle(), badgeX, y + ICON_FRAME - 8, 0xFFFFFFFF);
			}
			if (horizontal) x += ICON_FRAME + 1;
			else y += ICON_FRAME + 1;
		}
		guiGraphics.pose().popPose();
	}

	private static void drawCards(GuiGraphics guiGraphics, int screenX, int screenY) {
		Font font = Minecraft.getInstance().font;
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(screenX, screenY, 0);
		guiGraphics.pose().scale(SCALE, SCALE, 1);
		int x = 0;
		int y = 0;
		//Same squeeze as the vanilla inventory: past 5 effects the cards overlap instead of running off-screen.
		int step = cachedCards.length > 5 ? 132 / (cachedCards.length - 1) : CARD_HEIGHT + 1;
		for (Card card : cachedCards) {
			guiGraphics.blit(EFFECT_BACKGROUND, x, y, 0, 166, CARD_WIDTH, CARD_HEIGHT);
			if (card.icon() != null) Icons.draw(guiGraphics, card.icon(), x + 6, y + 7, 18);
			String title = font.plainSubstrByWidth(card.title(), TEXT_WIDTH);
			if (card.subtitle() == null) {
				guiGraphics.drawString(font, title, x + 28, y + 12, card.titleColor());
			} else {
				guiGraphics.drawString(font, title, x + 28, y + 6, card.titleColor());
				guiGraphics.drawString(font, font.plainSubstrByWidth(card.subtitle(), TEXT_WIDTH), x + 28, y + 16, SUBTITLE_COLOR);
			}
			y += step;
		}
		guiGraphics.pose().popPose();
	}

	/**
	 * <p>The full cards in the inventory, mirrored to the LEFT of the container: vanilla 1.20.1 already puts
	 * the potion effects on its right, so the two columns frame the inventory instead of overlapping. When
	 * the window is too narrow for a 120px card, falls back to the icon column, as vanilla does.</p>
	 */
	@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, value = Dist.CLIENT)
	public static class Inventory {
		@SubscribeEvent
		public static void onScreenRender(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
			if (!(event.getScreen() instanceof net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen<?> screen)) return;
			if (!refresh()) return;
			int room = screen.getGuiLeft() - 2;
			int cardWidth = Math.round(CARD_WIDTH * SCALE);
			int iconWidth = Math.round(ICON_FRAME * SCALE);
			if (room >= cardWidth) drawCards(event.getGuiGraphics(), room - cardWidth, screen.getGuiTop());
			else if (room >= iconWidth) drawIcons(event.getGuiGraphics(), room - iconWidth, screen.getGuiTop(), false);
		}
	}
}
