package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.TurnManager;
import net.hawthorn.dndsheets.command.TurnCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: botón de Iniciar/Siguiente/Cancelar/Terminar en TurnControlScreen
//(equivalente en GUI a /dndturns start|next|cancel|end).
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class TurnControlMessage {
	String action;

	public TurnControlMessage(String action) {
		this.action = action;
	}

	public TurnControlMessage(FriendlyByteBuf buffer) {
		this.action = buffer.readUtf();
	}

	public static void buffer(TurnControlMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.action);
	}

	public static void handler(TurnControlMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			ServerLevel level = dm.serverLevel();

			switch (message.action) {
				case "start" -> TurnCommand.startAt(level, dm.position(), TurnCommand.DEFAULT_RADIUS);
				case "next" -> TurnManager.next(level);
				case "cancel" -> TurnManager.cancel(level);
				case "end" -> TurnManager.end(level);
			}
		});
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(TurnControlMessage.class, TurnControlMessage::buffer, TurnControlMessage::new, TurnControlMessage::handler);
	}
}
