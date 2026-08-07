package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.DeathSaveScreen;
import net.hawthorn.dndsheets.client.gui.RestChoiceScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> cliente: abre/cierra una pantalla modal sin payload propio. Reemplaza DeathSaveOpenMessage,
//DeathSaveCloseMessage y RestChoiceOpenMessage, que eran 3 clases idénticas salvo qué pantalla accionaban.
public class ScreenActionMessage {
	public enum Action { DEATH_SAVE_OPEN, DEATH_SAVE_CLOSE, REST_CHOICE_OPEN }

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
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
			switch (message.action) {
				case DEATH_SAVE_OPEN -> DeathSaveScreen.open();
				case DEATH_SAVE_CLOSE -> DeathSaveScreen.close();
				case REST_CHOICE_OPEN -> RestChoiceScreen.open();
			}
		}));
		context.setPacketHandled(true);
	}
}
