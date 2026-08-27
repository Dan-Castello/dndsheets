package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.gui.GuideBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> cliente: la Guía (ver GuideBook). Con /dndguide la abre directamente; en el primer
//ingreso al mundo (SheetLoader.clientJoinedServer, firstJoin=true) ya NO — un libro a pantalla
//completa nada más entrar a un mundo nuevo se cerraba por reflejo sin leerse, y encima interrumpía el
//primer vistazo al mundo. Ahora es un toast de esquina con la tecla: informa sin secuestrar la pantalla.
public class TutorialOpenMessage {
	final boolean includeDmPages;
	//Al FINAL del payload — invariante 2, los campos nuevos nunca se insertan en medio.
	final boolean firstJoin;

	public TutorialOpenMessage(boolean includeDmPages) {
		this(includeDmPages, false);
	}

	public TutorialOpenMessage(boolean includeDmPages, boolean firstJoin) {
		this.includeDmPages = includeDmPages;
		this.firstJoin = firstJoin;
	}

	public TutorialOpenMessage(FriendlyByteBuf buffer) {
		this.includeDmPages = buffer.readBoolean();
		this.firstJoin = buffer.readBoolean();
	}

	public static void buffer(TutorialOpenMessage message, FriendlyByteBuf buffer) {
		buffer.writeBoolean(message.includeDmPages);
		buffer.writeBoolean(message.firstJoin);
	}

	public static void handler(TutorialOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		//No le robamos la pantalla a algo que el jugador ya tenga abierto (mismo criterio que
		//DndsheetsModKeyMappings.KeyEventListener para las teclas H/P).
		NetworkUtil.handleOnClient(context, () -> {
			Minecraft minecraft = Minecraft.getInstance();
			if (message.firstJoin) {
				SystemToast.add(minecraft.getToasts(), SystemToast.SystemToastIds.TUTORIAL_HINT,
					Component.translatable("gui.dndsheets.welcome_toast.title"),
					Component.translatable("gui.dndsheets.welcome_toast.body"));
			} else if (minecraft.screen == null) {
				GuideBook.open(message.includeDmPages);
			}
		});
	}
}
