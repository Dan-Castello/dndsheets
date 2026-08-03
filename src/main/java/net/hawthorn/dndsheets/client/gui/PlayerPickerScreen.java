package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.client.gui.components.ButtonListWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * <p>Primer paso genérico de cualquier herramienta del Panel de DM que actúa sobre OTRO jugador (conceder
 * un rasgo, ajustar oro/espacios/ventaja...): a quién. La lista de jugadores conectados ya la conoce el
 * cliente (tablist de {@link net.minecraft.client.multiplayer.ClientPacketListener}), así que no hace
 * falta pedírsela al servidor. Elegir uno pasa su UUID (como texto) a {@code onPick}, que decide qué
 * pantalla u mensaje viene después — así esta pantalla no necesita saber para qué se la está usando.</p>
 */
public class PlayerPickerScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int SPACING = 4;

	private final String prompt;
	private final Consumer<String> onPick;
	private ButtonListWidget list;

	private PlayerPickerScreen(String prompt, Consumer<String> onPick) {
		super(Component.literal("Elegir jugador"));
		this.prompt = prompt;
		this.onPick = onPick;
	}

	public static void open(String prompt, Consumer<String> onPick) {
		Minecraft.getInstance().setScreen(new PlayerPickerScreen(prompt, onPick));
	}

	@Override
	protected void init() {
		List<PlayerInfo> players = new ArrayList<>(this.minecraft.getConnection() != null ? this.minecraft.getConnection().getOnlinePlayers() : List.of());

		//Lista con scroll en vez de centrada a mano sin tope: con muchos jugadores conectados, el cálculo
		//viejo empujaba botones fuera de pantalla sin forma de alcanzarlos (ver AUDIT_UX.md).
		list = new ButtonListWidget((this.width - BUTTON_WIDTH) / 2, 30, BUTTON_WIDTH, this.height - 44, BUTTON_HEIGHT + SPACING);
		for (PlayerInfo info : players) {
			String uuid = info.getProfile().getId().toString();
			Button button = Button.builder(Component.literal(info.getProfile().getName()), b -> onPick.accept(uuid))
				.bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT).build();
			this.addWidget(button);
			list.addRow(button);
		}
		this.addRenderableWidget(list);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	//Ver PresetScreen.mouseScrolled: sin esto, el scroll solo funciona pasando el mouse por huecos sin
	//botón, se detiene en cuanto queda sobre una fila.
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		return list.mouseScrolled(mouseX, mouseY, delta) || super.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		guiGraphics.drawCenteredString(this.font, Component.literal(prompt), this.width / 2, 16, 0xFFFFFF);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
