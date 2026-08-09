package net.hawthorn.dndsheets.client;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>HUD del modo turnos, pensado sobre todo para quien nunca jugó D&D: de quién es el turno y la ronda
 * actual siempre que el modo turnos esté activo (así deja de ser "algo que hay que leer en el chat" y se
 * vuelve un estado visible todo el rato); si es el turno del jugador local, además si su acción sigue
 * disponible y cuánto movimiento le queda — calculado en el cliente contra el origen del turno que ya
 * manda {@link net.hawthorn.dndsheets.network.TurnStateMessage}, sin pedirle nada más al servidor.</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class TurnHudOverlay {
	private static final int DEFAULT_SPEED_FEET = 30;
	private static final double FEET_PER_BLOCK = 5.0;
	//speedBlocksFromClientSheet corre cada frame mientras es el turno del jugador local: el Pattern se
	//cachea en vez de recompilarse en cada frame.
	private static final Pattern SPEED_FEET_PATTERN = Pattern.compile("\\d+");

	@SubscribeEvent
	public static void registerOverlays(RegisterGuiOverlaysEvent event) {
		event.registerAboveAll("dndsheets_turns", (gui, guiGraphics, partialTick, width, height) -> render(guiGraphics, width));
	}

	private static void render(GuiGraphics guiGraphics, int screenWidth) {
		if (!TurnHudState.active()) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) return;
		var font = minecraft.font;
		int right = screenWidth - 6;
		int y = 6;
		int lineHeight = 10;

		drawRight(guiGraphics, font, "Ronda " + TurnHudState.round() + " — Turno: " + TurnHudState.currentName(), right, y, 0xFFD700);
		y += lineHeight;

		boolean yourTurn = TurnHudState.currentEntityId() == minecraft.player.getId();
		if (!yourTurn) {
			drawRight(guiGraphics, font, "Esperando...", right, y, 0xAAAAAA);
			return;
		}

		drawRight(guiGraphics, font, "¡Es tu turno!", right, y, 0x55FF55);
		y += lineHeight;

		boolean used = TurnHudState.actionUsed();
		drawRight(guiGraphics, font, used ? "Acción: usada" : "Acción: disponible", right, y, used ? 0xAAAAAA : 0x55FF55);
		y += lineHeight;

		double speedBlocks = speedBlocksFromClientSheet();
		double distanceMoved = minecraft.player.position().distanceTo(new Vec3(TurnHudState.originX(), TurnHudState.originY(), TurnHudState.originZ()));
		double remaining = Math.max(0, speedBlocks - distanceMoved);
		drawRight(guiGraphics, font, "Movimiento: " + Math.round(remaining) + "/" + Math.round(speedBlocks) + " bloques", right, y, 0x55FFFF);
		y += lineHeight;

		if (!used) {
			drawRight(guiGraphics, font, "Ataca o lanza un hechizo para actuar", right, y, 0xCCCCCC);
		}
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

	private static void drawRight(GuiGraphics guiGraphics, net.minecraft.client.gui.Font font, String text, int right, int y, int color) {
		guiGraphics.drawString(font, text, right - font.width(text), y, color);
	}
}
