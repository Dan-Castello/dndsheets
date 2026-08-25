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
 * <p>Cliente -> servidor: el jugador eligió una clase en {@link net.hawthorn.dndsheets.client.gui.PresetScreen}
 * (en modo multiclase) para subir un nivel EN ESA clase en su propia hoja — mismo botón "Multiclasear" de
 * {@code CharacterSheetScreen}, mismo camino que ya usaba {@code /dndsheet multiclass}
 * ({@link SheetCommand#applyMulticlass}).</p>
 *
 * <p>A diferencia de {@link PresetApplyMessage} (self, sin candado: elegir el preset inicial no requiere
 * ser DM), esto SÍ lo requiere: en 5e la multiclase la concede quien lleva la mesa, y
 * {@code /dndsheet multiclass} ya vive bajo {@code .requires(source -> DndsheetsMod.canActAsDm(source))}.
 * El botón de la ficha se muestra siempre (el cliente no puede saber {@code Config.soloMode()} sin que se
 * le sincronice), así que la autoridad real vive aquí, del lado servidor — y a diferencia del guard mudo de
 * {@code NetworkUtil.handleOnServerAsDm}, aquí SÍ hace falta avisar por chat: quien no es DM (y no hay modo
 * Solo) pulsa el botón, no pasa nada visible, y eso se lee como un botón roto en vez de una acción
 * rechazada.</p>
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
