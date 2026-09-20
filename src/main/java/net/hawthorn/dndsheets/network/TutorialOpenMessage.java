package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.gui.GuideBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Server -> client: the Guide (see GuideBook). With /dndguide it opens it directly; on first
//joining the world (SheetLoader.clientJoinedServer, firstJoin=true) it no longer does — a
//full-screen book right as you enter a new world got closed reflexively without being read, and it
//also interrupted the first look at the world. Now it's a corner toast with the keybind: informs
//without hijacking the screen.
public class TutorialOpenMessage {
	final boolean includeDmPages;
	//At the END of the payload — invariant 2, new fields are never inserted in the middle.
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
		//We don't steal the screen from something the player already has open (same criterion as
		//DndsheetsModKeyMappings.KeyEventListener for the H/P keys).
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
