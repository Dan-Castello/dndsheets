package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.RestVoteScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> cliente (todos): alguien propuso un descanso, que voten.
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class RestVoteOpenMessage {
	final String proposerName;
	final String typeLabel;

	public RestVoteOpenMessage(String proposerName, String typeLabel) {
		this.proposerName = proposerName;
		this.typeLabel = typeLabel;
	}

	public RestVoteOpenMessage(FriendlyByteBuf buffer) {
		this.proposerName = buffer.readUtf();
		this.typeLabel = buffer.readUtf();
	}

	public static void buffer(RestVoteOpenMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.proposerName);
		buffer.writeUtf(message.typeLabel);
	}

	public static void handler(RestVoteOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> RestVoteScreen.open(message.proposerName, message.typeLabel)));
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(RestVoteOpenMessage.class, RestVoteOpenMessage::buffer, RestVoteOpenMessage::new, RestVoteOpenMessage::handler);
	}
}
