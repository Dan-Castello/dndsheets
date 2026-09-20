package net.hawthorn.dndsheets.dungeon;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import net.minecraft.network.FriendlyByteBuf;

import net.hawthorn.dndsheets.dungeon.network.*;

import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.BiConsumer;

/**
 * <p>Entry point of the dungeon toolkit addon: its own modid ({@code dndsheets_dungeon}), its own
 * network channel. It isn't really a standalone mod — {@code mods.toml} declares it with a MANDATORY
 * dependency on {@code dndsheets} — but it needs its own channel because a {@link SimpleChannel}
 * belongs to a modid, and mixing the messages of two mods into the core's channel would have tied
 * this addon's network version to the core's forever (see the PROTOCOL_VERSION invariant in
 * {@code DndsheetsMod}).</p>
 */
@Mod("dndsheets_dungeon")
public class DndsheetsDungeonMod {
	public static final Logger LOGGER = LogManager.getLogger(DndsheetsDungeonMod.class);
	public static final String MODID = "dndsheets_dungeon";

	//Same pattern as DndsheetsMod.PROTOCOL_VERSION: bumps when the shape or order of THIS channel's
	//messages changes. Starts at "1" because the channel is new — it doesn't inherit the core's "16".
	private static final String PROTOCOL_VERSION = "1";
	public static final SimpleChannel PACKET_HANDLER = NetworkRegistry.newSimpleChannel(
		new net.minecraft.resources.ResourceLocation(MODID, MODID),
		() -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);
	private static int messageID = 0;

	public DndsheetsDungeonMod() {
		IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
		bus.addListener(DndsheetsDungeonMod::registerNetworkMessages);
	}

	public static <T> void addNetworkMessage(Class<T> messageType, BiConsumer<T, FriendlyByteBuf> encoder,
			Function<FriendlyByteBuf, T> decoder, BiConsumer<T, Supplier<NetworkEvent.Context>> messageConsumer) {
		PACKET_HANDLER.registerMessage(messageID, messageType, encoder, decoder, messageConsumer);
		messageID++;
	}

	//Same as in the core: each message's network id is its registration order. Always add to the
	//end of this list, never in the middle.
	private static void registerNetworkMessages(FMLCommonSetupEvent event) {
		addNetworkMessage(DungeonGenerateMessage.class, DungeonGenerateMessage::buffer, DungeonGenerateMessage::new, DungeonGenerateMessage::handler);
		addNetworkMessage(DungeonJigsawConfigureMessage.class, DungeonJigsawConfigureMessage::buffer, DungeonJigsawConfigureMessage::new, DungeonJigsawConfigureMessage::handler);
		addNetworkMessage(DungeonJigsawConfigureOpenMessage.class, DungeonJigsawConfigureOpenMessage::buffer, DungeonJigsawConfigureOpenMessage::new, DungeonJigsawConfigureOpenMessage::handler);
		addNetworkMessage(DungeonPieceAddOpenMessage.class, DungeonPieceAddOpenMessage::buffer, DungeonPieceAddOpenMessage::new, DungeonPieceAddOpenMessage::handler);
		addNetworkMessage(DungeonPieceCaptureMessage.class, DungeonPieceCaptureMessage::buffer, DungeonPieceCaptureMessage::new, DungeonPieceCaptureMessage::handler);
		addNetworkMessage(DungeonPieceListMessage.class, DungeonPieceListMessage::buffer, DungeonPieceListMessage::new, DungeonPieceListMessage::handler);
		addNetworkMessage(DungeonPieceListRequestMessage.class, DungeonPieceListRequestMessage::buffer, DungeonPieceListRequestMessage::new, DungeonPieceListRequestMessage::handler);
		addNetworkMessage(DungeonPieceRemoveMessage.class, DungeonPieceRemoveMessage::buffer, DungeonPieceRemoveMessage::new, DungeonPieceRemoveMessage::handler);
		addNetworkMessage(DungeonPieceUpdateMessage.class, DungeonPieceUpdateMessage::buffer, DungeonPieceUpdateMessage::new, DungeonPieceUpdateMessage::handler);
		addNetworkMessage(DungeonTraceCaptureMessage.class, DungeonTraceCaptureMessage::buffer, DungeonTraceCaptureMessage::new, DungeonTraceCaptureMessage::handler);
	}
}
