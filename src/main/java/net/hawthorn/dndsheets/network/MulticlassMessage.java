package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.command.SheetCommand;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * <p>Client -> server: the player chose a class in {@link net.hawthorn.dndsheets.client.gui.PresetScreen}
 * (in multiclass mode) to gain a level IN THAT class on their own sheet — same "Multiclass" button as
 * {@code CharacterSheetScreen}, same path already used by {@code /dndsheet multiclass}
 * ({@link SheetCommand#applyMulticlass}).</p>
 *
 * <p>Unlike {@link PresetApplyMessage} (self, no lock: choosing the initial preset doesn't require being
 * DM), this one DOES require it: in 5e multiclassing is granted by whoever runs the table, and
 * {@code /dndsheet multiclass} already lives under {@code .requires(source -> DndsheetsMod.canActAsDm(source))}.
 * The sheet's button is always shown (the client has no way to know {@code Config.soloMode()} without it
 * being synced), so the real authority lives here, server-side — and unlike the silent guard in
 * {@code NetworkUtil.handleOnServerAsDm}, here a chat notice IS needed: someone who isn't DM (and Solo
 * mode isn't on) presses the button, nothing visible happens, and that reads as a broken button instead
 * of a rejected action.</p>
 */
public class MulticlassMessage {
	String classId;

	public MulticlassMessage(String classId) {
		this.classId = classId;
	}

	public MulticlassMessage(FriendlyByteBuf buffer) {
		this.classId = buffer.readUtf();
	}

	public static void buffer(MulticlassMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.classId);
	}

	public static void handler(MulticlassMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer player = context.getSender();
			if (player == null) return;

			if (!DndsheetsMod.canActAsDm(player)) {
				player.sendSystemMessage(Component.translatable("chat.dndsheets.multiclass.denied")
					.withStyle(ChatFormatting.RED));
				return;
			}

			SheetCommand.applyMulticlass(player, message.classId);
		});
	}
}
