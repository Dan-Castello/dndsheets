package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.network.DeathSaveGiveUpMessage;
import net.hawthorn.dndsheets.network.DeathSaveRollMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * <p>Ventana forzada mientras el personaje está caído a 0 PG: no se puede cerrar con ESC, solo el
 * servidor la cierra cuando el jugador se estabiliza (3 éxitos, un 20 natural, o alguien lo reanima)
 * o muere de verdad (3 fallos). Ver {@link net.hawthorn.dndsheets.DeathSaveManager}.</p>
 */
public class DeathSaveScreen extends ModalDialogScreen {
	private static final int WIDTH = 240;
	private static final int HEIGHT = 115;

	protected DeathSaveScreen() {
		super(Component.literal("Salvación de Muerte"), WIDTH, HEIGHT);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new DeathSaveScreen());
	}

	public static void close() {
		if (Minecraft.getInstance().screen instanceof DeathSaveScreen) {
			Minecraft.getInstance().setScreen(null);
		}
	}

	@Override
	protected void init() {
		addModalButton(20, 60, WIDTH - 40, 20, Component.literal("Tirar salvación de muerte"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(new DeathSaveRollMessage())
		);
		//Dejarse morir: para quien no quiere seguir tirando (personaje que ya cumplió su arco, sesión que
		//se tiene que cortar, etc.) — mata de verdad al instante, mismo camino que 3 fallos de salvación
		//(ver DeathSaveManager.handleGiveUpRequest). Sin confirmación extra: un solo clic, igual que tirar.
		addModalButton(20, 85, WIDTH - 40, 20, Component.literal("Dejarse morir").withStyle(ChatFormatting.DARK_RED), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(new DeathSaveGiveUpMessage())
		);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderPanel(guiGraphics);

		int top = dialogTop();
		guiGraphics.drawCenteredString(this.font, Component.literal("¡Estás caído!"), this.width / 2, top + 8, 0xFFFFFF);

		JsonObject sheet = SheetLoader.getClientSheet();
		int successes = sheet != null && sheet.has("deathSaveSuccesses") ? sheet.get("deathSaveSuccesses").getAsInt() : 0;
		int failures = sheet != null && sheet.has("deathSaveFailures") ? sheet.get("deathSaveFailures").getAsInt() : 0;

		guiGraphics.drawCenteredString(this.font, Component.literal("Éxitos: " + marks(successes) + "     Fallos: " + marks(failures)), this.width / 2, top + 24, 0xAAAAAA);
		guiGraphics.drawCenteredString(this.font, Component.literal("Otro jugador puede reanimarte interactuando contigo."), this.width / 2, top + 38, 0x888888);

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}

	private static String marks(int count) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < 3; i++) builder.append(i < count ? "●" : "○");
		return builder.toString();
	}
}
