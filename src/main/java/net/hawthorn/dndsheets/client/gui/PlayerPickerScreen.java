package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * <p>Generic first step of any DM Panel tool that acts on ANOTHER player (grant a trait, adjust
 * gold/slots/advantage...): who. The client already knows the list of connected players (the tablist
 * from {@link net.minecraft.client.multiplayer.ClientPacketListener}), so there's no need to ask the
 * server for it. Picking one passes their UUID (as text) to {@code onPick}, which decides what screen
 * or message comes next — so this screen doesn't need to know what it's being used for.</p>
 */
public class PlayerPickerScreen extends ListPickerScreen {
	private final Consumer<String> onPick;

	private PlayerPickerScreen(Component prompt, Consumer<String> onPick, Screen parent) {
		super(prompt, parent);
		this.onPick = onPick;
	}

	//The prompt comes in as a Component so it can be translatable: it used to be a plain String, which
	//forced the eleven call sites that open it to hardcode their title in Spanish.
	public static void open(Component prompt, Consumer<String> onPick) {
		Minecraft.getInstance().setScreen(new PlayerPickerScreen(prompt, onPick, Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
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
