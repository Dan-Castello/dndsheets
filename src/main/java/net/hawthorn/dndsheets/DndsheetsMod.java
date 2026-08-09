
package net.hawthorn.dndsheets;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.PacketDistributor;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import net.hawthorn.dndsheets.init.DndsheetsModSounds;
import net.hawthorn.dndsheets.init.DndsheetsModMenus;
import net.hawthorn.dndsheets.init.DndsheetsModCreativeTab;
import net.hawthorn.dndsheets.network.*;

import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.List;
import java.util.Collection;
import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.UUID;

@Mod("dndsheets")
public class DndsheetsMod {
	public static final Logger LOGGER = LogManager.getLogger(DndsheetsMod.class);
	public static final String MODID = "dndsheets";

	public DndsheetsMod() {
		MinecraftForge.EVENT_BUS.register(this);
		IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
		DndsheetsModSounds.REGISTRY.register(bus);

		DndsheetsModMenus.REGISTRY.register(bus);
		DndsheetsModCreativeTab.REGISTRY.register(bus);
		bus.addListener(DndsheetsMod::registerNetworkMessages);

		ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "dndsheets-common.toml");
	}

	private static final String PROTOCOL_VERSION = "1";
	public static final SimpleChannel PACKET_HANDLER = NetworkRegistry.newSimpleChannel(new ResourceLocation(MODID, MODID), () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);
	private static int messageID = 0;

	public static <T> void addNetworkMessage(Class<T> messageType, BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder, BiConsumer<T, Supplier<NetworkEvent.Context>> messageConsumer) {
		PACKET_HANDLER.registerMessage(messageID, messageType, encoder, decoder, messageConsumer);
		messageID++;
	}

	//Registro centralizado de las ~40 clases de network/: antes cada una se autorregistraba con su propio
	//@Mod.EventBusSubscriber + método registerMessage(FMLCommonSetupEvent) — ~5 líneas de boilerplate
	//idéntico repetidas 40 veces. SimpleChannel exige que cada clase siga teniendo su propio buffer/
	//constructor/handler (no se puede genericar eso), pero el PUNTO donde se registran sí es uno solo.
	private static void registerNetworkMessages(FMLCommonSetupEvent event) {
		addNetworkMessage(AddCustomAttackMessage.class, AddCustomAttackMessage::buffer, AddCustomAttackMessage::new, AddCustomAttackMessage::handler);
		addNetworkMessage(AdvancedRollEditorOpenMessage.class, AdvancedRollEditorOpenMessage::buffer, AdvancedRollEditorOpenMessage::new, AdvancedRollEditorOpenMessage::handler);
		addNetworkMessage(CharacterOptionsListMessage.class, CharacterOptionsListMessage::buffer, CharacterOptionsListMessage::new, CharacterOptionsListMessage::handler);
		addNetworkMessage(CharacterOptionsRequestMessage.class, CharacterOptionsRequestMessage::buffer, CharacterOptionsRequestMessage::new, CharacterOptionsRequestMessage::handler);
		addNetworkMessage(CharacterSheetOpenMessage.class, CharacterSheetOpenMessage::buffer, CharacterSheetOpenMessage::new, CharacterSheetOpenMessage::handler);
		addNetworkMessage(ClearCustomAttacksMessage.class, ClearCustomAttacksMessage::buffer, ClearCustomAttacksMessage::new, ClearCustomAttacksMessage::handler);
		addNetworkMessage(DeathSaveRollMessage.class, DeathSaveRollMessage::buffer, DeathSaveRollMessage::new, DeathSaveRollMessage::handler);
		addNetworkMessage(MonsterActionChooseMessage.class, MonsterActionChooseMessage::buffer, MonsterActionChooseMessage::new, MonsterActionChooseMessage::handler);
		addNetworkMessage(MonsterActionOpenMessage.class, MonsterActionOpenMessage::buffer, MonsterActionOpenMessage::new, MonsterActionOpenMessage::handler);
		addNetworkMessage(PassivePerceptionRequestMessage.class, PassivePerceptionRequestMessage::buffer, PassivePerceptionRequestMessage::new, PassivePerceptionRequestMessage::handler);
		addNetworkMessage(PresetApplyMessage.class, PresetApplyMessage::buffer, PresetApplyMessage::new, PresetApplyMessage::handler);
		addNetworkMessage(PresetApplyToMessage.class, PresetApplyToMessage::buffer, PresetApplyToMessage::new, PresetApplyToMessage::handler);
		addNetworkMessage(PresetListMessage.class, PresetListMessage::buffer, PresetListMessage::new, PresetListMessage::handler);
		addNetworkMessage(PresetListRequestMessage.class, PresetListRequestMessage::buffer, PresetListRequestMessage::new, PresetListRequestMessage::handler);
		addNetworkMessage(RemoveCustomAttackMessage.class, RemoveCustomAttackMessage::buffer, RemoveCustomAttackMessage::new, RemoveCustomAttackMessage::handler);
		addNetworkMessage(RestProposeMessage.class, RestProposeMessage::buffer, RestProposeMessage::new, RestProposeMessage::handler);
		addNetworkMessage(RestVoteCloseMessage.class, RestVoteCloseMessage::buffer, RestVoteCloseMessage::new, RestVoteCloseMessage::handler);
		addNetworkMessage(RestVoteOpenMessage.class, RestVoteOpenMessage::buffer, RestVoteOpenMessage::new, RestVoteOpenMessage::handler);
		addNetworkMessage(RestVoteResponseMessage.class, RestVoteResponseMessage::buffer, RestVoteResponseMessage::new, RestVoteResponseMessage::handler);
		addNetworkMessage(RollEditorOpenMessage.class, RollEditorOpenMessage::buffer, RollEditorOpenMessage::new, RollEditorOpenMessage::handler);
		addNetworkMessage(ScreenActionMessage.class, ScreenActionMessage::buffer, ScreenActionMessage::new, ScreenActionMessage::handler);
		addNetworkMessage(SheetAdvantageMessage.class, SheetAdvantageMessage::buffer, SheetAdvantageMessage::new, SheetAdvantageMessage::handler);
		addNetworkMessage(SheetClientMessage.class, SheetClientMessage::buffer, SheetClientMessage::new, SheetClientMessage::handler);
		addNetworkMessage(SheetDamageAffinityMessage.class, SheetDamageAffinityMessage::buffer, SheetDamageAffinityMessage::new, SheetDamageAffinityMessage::handler);
		addNetworkMessage(SheetFieldUpdateMessage.class, SheetFieldUpdateMessage::buffer, SheetFieldUpdateMessage::new, SheetFieldUpdateMessage::handler);
		addNetworkMessage(SheetGoldMessage.class, SheetGoldMessage::buffer, SheetGoldMessage::new, SheetGoldMessage::handler);
		addNetworkMessage(SheetLevelMessage.class, SheetLevelMessage::buffer, SheetLevelMessage::new, SheetLevelMessage::handler);
		addNetworkMessage(SheetPactMessage.class, SheetPactMessage::buffer, SheetPactMessage::new, SheetPactMessage::handler);
		addNetworkMessage(SheetRollButtonMessage.class, SheetRollButtonMessage::buffer, SheetRollButtonMessage::new, SheetRollButtonMessage::handler);
		addNetworkMessage(SheetServerMessage.class, SheetServerMessage::buffer, SheetServerMessage::new, SheetServerMessage::handler);
		addNetworkMessage(SheetSlotsMessage.class, SheetSlotsMessage::buffer, SheetSlotsMessage::new, SheetSlotsMessage::handler);
		addNetworkMessage(SheetSummaryMessage.class, SheetSummaryMessage::buffer, SheetSummaryMessage::new, SheetSummaryMessage::handler);
		addNetworkMessage(SheetSummaryRequestMessage.class, SheetSummaryRequestMessage::buffer, SheetSummaryRequestMessage::new, SheetSummaryRequestMessage::handler);
		addNetworkMessage(SpawnGenericMessage.class, SpawnGenericMessage::buffer, SpawnGenericMessage::new, SpawnGenericMessage::handler);
		addNetworkMessage(SpellCastMessage.class, SpellCastMessage::buffer, SpellCastMessage::new, SpellCastMessage::handler);
		addNetworkMessage(TraitGrantMessage.class, TraitGrantMessage::buffer, TraitGrantMessage::new, TraitGrantMessage::handler);
		addNetworkMessage(TraitListMessage.class, TraitListMessage::buffer, TraitListMessage::new, TraitListMessage::handler);
		addNetworkMessage(TraitListRequestMessage.class, TraitListRequestMessage::buffer, TraitListRequestMessage::new, TraitListRequestMessage::handler);
		addNetworkMessage(TurnControlMessage.class, TurnControlMessage::buffer, TurnControlMessage::new, TurnControlMessage::handler);
		addNetworkMessage(TurnEffectApplyMessage.class, TurnEffectApplyMessage::buffer, TurnEffectApplyMessage::new, TurnEffectApplyMessage::handler);
		addNetworkMessage(TurnStateMessage.class, TurnStateMessage::buffer, TurnStateMessage::new, TurnStateMessage::handler);
	}

	//Patrón repetido en los mensajes cliente(DM)->servidor que actúan sobre OTRO jugador (SheetLevelMessage,
	//SheetGoldMessage, SheetSlotsMessage, SheetAdvantageMessage, SheetDamageAffinityMessage, SheetPactMessage,
	//TraitGrantMessage, PresetApplyToMessage): comprobar que quien envía el mensaje es un operador y que el
	//jugador objetivo sigue conectado, antes de delegar. Llamar dentro de context.enqueueWork(...).
	public static void withDmTarget(NetworkEvent.Context context, String targetUuid, Consumer<ServerPlayer> action) {
		ServerPlayer dm = context.getSender();
		if (dm == null || !dm.hasPermissions(2)) return;
		UUID uuid;
		try {
			uuid = UUID.fromString(targetUuid);
		} catch (IllegalArgumentException e) {
			//Un operador con un cliente roto/modificado puede mandar un UUID malformado; se descarta el
			//mensaje en vez de tumbar el hilo del servidor con una excepción sin capturar.
			return;
		}
		ServerPlayer target = dm.getServer().getPlayerList().getPlayer(uuid);
		if (target != null) action.accept(target);
	}

	//Envía un parche de unos pocos campos de la hoja (ver network.SheetFieldUpdateMessage) en vez de la
	//hoja JSON completa — para cambios acotados como consumir ventaja/inspiración o gastar un espacio de
	//conjuro, que antes reenviaban toda la hoja en cada golpe/hechizo. Ver AUDIT_TECHNICAL.md M-NET-1.
	public static void sendSheetFieldUpdate(ServerPlayer player, JsonObject patch) {
		PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetFieldUpdateMessage(patch.toString().getBytes()));
	}

	private static final Collection<AbstractMap.SimpleEntry<Runnable, Integer>> workQueue = new ConcurrentLinkedQueue<>();

	public static void queueServerWork(int tick, Runnable action) {
		workQueue.add(new AbstractMap.SimpleEntry(action, tick));
	}

	@SubscribeEvent
	public void tick(TickEvent.ServerTickEvent event) {
		if (event.phase == TickEvent.Phase.END) {
			List<AbstractMap.SimpleEntry<Runnable, Integer>> actions = new ArrayList<>();
			workQueue.forEach(work -> {
				work.setValue(work.getValue() - 1);
				if (work.getValue() == 0)
					actions.add(work);
			});
			actions.forEach(e -> e.getKey().run());
			workQueue.removeAll(actions);
		}
	}
}
