package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.gui.GuideBook;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> cliente: abre la Guía (ver GuideBook), tanto para el primer ingreso al mundo (SheetLoader.
//clientJoinedServer) como para /dndguide bajo demanda.
public class TutorialOpenMessage {
	final boolean includeDmPages;

	public TutorialOpenMessage(boolean includeDmPages) {
		this.includeDmPages = includeDmPages;
	}

	public TutorialOpenMessage(FriendlyByteBuf buffer) {
		this.includeDmPages = buffer.readBoolean();
	}

	public static void buffer(TutorialOpenMessage message, FriendlyByteBuf buffer) {
		buffer.writeBoolean(message.includeDmPages);
	}

	public static void handler(TutorialOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		//No le robamos la pantalla a algo que el jugador ya tenga abierto (mismo criterio que
		//DndsheetsModKeyMappings.KeyEventListener para las teclas H/P).
		NetworkUtil.handleOnClient(context, () -> {
			if (Minecraft.getInstance().screen == null) GuideBook.open(message.includeDmPages);
		});
	}
}
