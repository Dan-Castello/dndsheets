package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.DeathSaveScreen;
import net.hawthorn.dndsheets.client.gui.RestChoiceScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> cliente: abre/cierra una pantalla modal sin payload propio. Reemplaza DeathSaveOpenMessage,
//DeathSaveCloseMessage y RestChoiceOpenMessage, que eran 3 clases idénticas salvo qué pantalla accionaban.
public class ScreenActionMessage {
	//Al final, nunca en medio: writeEnum viaja por ordinal (invariante 2 de PROJECT_CONTEXT.md).
	public enum Action { DEATH_SAVE_OPEN, DEATH_SAVE_CLOSE, REST_CHOICE_OPEN, COMPENDIUM_OPEN, TURN_ACTION_OPEN }

	final Action action;

	public ScreenActionMessage(Action action) {
		this.action = action;
	}

	public ScreenActionMessage(FriendlyByteBuf buffer) {
		this.action = buffer.readEnum(Action.class);
	}

	public static void buffer(ScreenActionMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.action);
	}

	public static void handler(ScreenActionMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> {
			switch (message.action) {
				case DEATH_SAVE_OPEN -> DeathSaveScreen.open();
				case DEATH_SAVE_CLOSE -> DeathSaveScreen.close();
				case REST_CHOICE_OPEN -> RestChoiceScreen.open();
				case COMPENDIUM_OPEN -> net.hawthorn.dndsheets.client.gui.CompendiumScreen.open();
				case TURN_ACTION_OPEN -> net.hawthorn.dndsheets.client.gui.TurnActionScreen.open();
			}
		});
	}
}
