package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.Minecraft;
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
public class PlayerPickerScreen extends ListPickerScreen {
	private final Consumer<String> onPick;

	private PlayerPickerScreen(String prompt, Consumer<String> onPick, Screen parent) {
		super(Component.literal(prompt), parent);
		this.onPick = onPick;
	}

	public static void open(String prompt, Consumer<String> onPick) {
		Minecraft.getInstance().setScreen(new PlayerPickerScreen(prompt, onPick, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildRows() {
		List<PlayerInfo> players = new ArrayList<>(this.minecraft.getConnection() != null ? this.minecraft.getConnection().getOnlinePlayers() : List.of());
		for (PlayerInfo info : players) {
			String uuid = info.getProfile().getId().toString();
			addRow(Component.literal(info.getProfile().getName()), b -> onPick.accept(uuid));
		}
	}
}
