package net.hawthorn.dndsheets.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.GuiStyle;
import net.hawthorn.dndsheets.client.gui.Icons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>The d20 on screen: it spins while a random face flickers, then lands with the natural roll, the total
 * under it and, if it was a 20 or a 1, the word that goes with it. About 3 seconds, then it fades.
 * Cosmetic only (see {@code DiceRollMessage}); a new roll replaces the one still showing.</p>
 *
 * <p>No 3D and no model: the die is the game-icons glyph ({@link Icons#stat}) rotated and scaled with the
 * GUI pose stack, which keeps it inside the same screen rules as the rest of the HUD.</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class DiceRollOverlay {
	private static final long TUMBLE_MS = 850;
	private static final long HOLD_MS = 1900;
	private static final long FADE_MS = 650;
	//Was 56: 30% smaller, in line with the rest of the HUD, so the die doesn't cover what you're aiming at.
	private static final int DIE = 40;

	private static long startedAt = -1;
	private static int natural, total, kind;
	private static String who = "";

	public static void show(int natural, int total, int kind, String who) {
		DiceRollOverlay.natural = natural;
		DiceRollOverlay.total = total;
		DiceRollOverlay.kind = kind;
		DiceRollOverlay.who = who;
		startedAt = System.currentTimeMillis();
	}

	@SubscribeEvent
	public static void registerOverlays(RegisterGuiOverlaysEvent event) {
		event.registerAboveAll("dndsheets_dice", (gui, guiGraphics, partialTick, width, height) -> render(guiGraphics, width, height));
	}

	private static void render(GuiGraphics guiGraphics, int width, int height) {
		if (startedAt < 0 || Minecraft.getInstance().options.hideGui) return;
		long elapsed = System.currentTimeMillis() - startedAt;
		if (elapsed > TUMBLE_MS + HOLD_MS + FADE_MS) {
			startedAt = -1;
			return;
		}

		Font font = Minecraft.getInstance().font;
		float alpha = elapsed <= TUMBLE_MS + HOLD_MS ? 1.0f : 1.0f - (elapsed - TUMBLE_MS - HOLD_MS) / (float) FADE_MS;
		boolean tumbling = elapsed < TUMBLE_MS;

		//Ease-out: it spins fast and slows into the landing. The shown face flickers while it spins.
		float progress = Math.min(1.0f, elapsed / (float) TUMBLE_MS);
		float eased = 1.0f - (1.0f - progress) * (1.0f - progress) * (1.0f - progress);
		float angle = tumbling ? (1.0f - eased) * 900.0f : 0.0f;
		int face = tumbling ? 1 + (int) ((elapsed / 55) * 7919 % 20) : natural;
		//A little pop right after landing.
		float scale = tumbling ? 0.75f + 0.25f * eased
			: 1.0f + 0.22f * (float) Math.max(0.0, 1.0 - (elapsed - TUMBLE_MS) / 260.0);

		int centerX = width / 2;
		int centerY = (int) (height * 0.27f);
		int tint = kind == 1 ? 0xFFFFD34D : kind == 2 ? GuiStyle.BAD_COLOR : 0xFFFFFFFF;

		RenderSystem.enableBlend();
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(centerX, centerY, 0);
		guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(angle));
		guiGraphics.pose().scale(scale, scale, 1.0f);
		RenderSystem.setShaderColor(((tint >> 16) & 0xFF) / 255f, ((tint >> 8) & 0xFF) / 255f, (tint & 0xFF) / 255f, alpha);
		Icons.draw(guiGraphics, Icons.stat("d20"), -DIE / 2, -DIE / 2, DIE);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		guiGraphics.pose().popPose();

		int a = (int) (alpha * 255) << 24;
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(centerX, centerY - 4, 0);
		guiGraphics.pose().scale(scale * 1.5f, scale * 1.5f, 1.0f);
		guiGraphics.drawCenteredString(font, GuiStyle.title(Component.literal(Integer.toString(face))), 0, 0, (0x00FFFFFF & GuiStyle.TITLE_COLOR) | a);
		guiGraphics.pose().popPose();

		if (!tumbling) {
			int textY = centerY + DIE / 2 + 6;
			guiGraphics.drawCenteredString(font, Component.translatable("hud.dndsheets.dice.total", net.hawthorn.dndsheets.ContentNames.of(who), total), centerX, textY, (0x00FFFFFF & GuiStyle.SUBTITLE_COLOR) | a);
			if (kind != 0) {
				guiGraphics.drawCenteredString(font, Component.translatable(kind == 1 ? "hud.dndsheets.dice.crit" : "hud.dndsheets.dice.fumble"),
					centerX, textY + 11, (0x00FFFFFF & tint) | a);
			}
		}
		RenderSystem.disableBlend();
	}
}
