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
 * <p>Punto de entrada del addon del toolkit de mazmorras: modid propio ({@code dndsheets_dungeon}),
 * canal de red propio. No es un mod independiente de verdad — {@code mods.toml} lo declara con
 * dependencia OBLIGATORIA de {@code dndsheets} — pero necesita su propio canal porque un
 * {@link SimpleChannel} pertenece a un modid, y mezclar los mensajes de dos mods en el canal del
 * core habria atado la version de red de este addon a la del core para siempre (ver la invariante de
 * PROTOCOL_VERSION en {@code DndsheetsMod}).</p>
 */
@Mod("dndsheets_dungeon")
public class DndsheetsDungeonMod {
	public static final Logger LOGGER = LogManager.getLogger(DndsheetsDungeonMod.class);
	public static final String MODID = "dndsheets_dungeon";

	//Mismo patron que DndsheetsMod.PROTOCOL_VERSION: sube cuando cambia la forma o el orden de los
	//mensajes de ESTE canal. Empieza en "1" porque el canal es nuevo — no hereda el "16" del core.
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

	//Igual que en el core: el id de red de cada mensaje es su orden de registro. Añade siempre al
	//final de esta lista, nunca en medio.
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
