
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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;

import net.hawthorn.dndsheets.init.DndsheetsModSounds;
import net.hawthorn.dndsheets.init.DndsheetsModMenus;
import net.hawthorn.dndsheets.init.DndsheetsModCreativeTab;
import net.hawthorn.dndsheets.network.*;

import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.UUID;

@Mod("dndsheets")
public class DndsheetsMod {
	public static final Logger LOGGER = LogManager.getLogger(DndsheetsMod.class);

	/**
	 * <p>El escritor de JSON con sangria que usa todo el mod. Vive aqui por el mismo motivo que LOGGER y
	 * PACKET_HANDLER: es infraestructura compartida, no de ningun subsistema.</p>
	 *
	 * <p>Construir un Gson no es gratis —arma toda la lista de TypeAdapterFactory— y se estaba haciendo
	 * <b>en cada escritura</b> en seis sitios. El peor era SheetLoader.saveAll(), que corre cada 5 minutos
	 * y repetia la construccion UNA VEZ POR HOJA: con seis jugadores y varios PNJ, un puñado de Gson
	 * nuevos en el mismo tick. Es inmutable y seguro entre hilos, asi que una sola instancia sirve.</p>
	 */
	public static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final String MODID = "dndsheets";

	public DndsheetsMod() {
		MinecraftForge.EVENT_BUS.register(this);
		IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
		net.hawthorn.dndsheets.init.DndsheetsModItems.REGISTRY.register(bus);
		DndsheetsModSounds.REGISTRY.register(bus);

		DndsheetsModMenus.REGISTRY.register(bus);
		DndsheetsModCreativeTab.REGISTRY.register(bus);
		bus.addListener(DndsheetsMod::registerNetworkMessages);

		ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "dndsheets-common.toml");
	}

	//Sube a "2": registerNetworkMessages() fusionó 6 mensajes en SheetAdjustMessage (ver F14 del audit).
	//messageID se asigna por ORDEN de registro, no por constante fija por clase — fusionar/quitar/añadir
	//una entrada renumera TODO lo que se registra después en la lista, no solo lo tocado. Sin subir esto,
	//un cliente y un servidor de versiones de mod distintas igual pasarían el handshake (mismo string de
	//antes) y acabarían desalineados en el id de mensaje para cualquier cosa después del punto de cambio,
	//en vez de que Forge los rechace limpio al conectar por versión de protocolo incompatible.
	//Sube a "3": SheetSummaryMessage gana un campo en el cable (las condiciones activas del objetivo),
	//SheetAdjustMessage.Field gana la constante CONDITION, y se registran BrowseActionMessage/
	//BrowseListMessage. Lo primero es lo verdaderamente peligroso: un campo más en un mensaje ya existente
	//no cambia ningún id, así que sin subir esto el handshake pasaría y el cliente antiguo leería ese
	//mensaje corrido un campo, en silencio y con datos plausibles, en vez de fallar limpio al conectar.
	//Sube a "4": se registra TurnActionMessage y ScreenActionMessage.Action gana TURN_ACTION_OPEN. Lo
	//segundo es lo peligroso de verdad: un cliente antiguo leeria ese ordinal como una accion que no
	//conoce en vez de fallar limpio al conectar.
	//Sube a "5": se registra AbilityImprovementMessage y ScreenActionMessage.Action gana
	//ABILITY_IMPROVEMENT_OPEN. Lo segundo es lo peligroso: un cliente antiguo leeria ese ordinal como una
	//accion que no conoce en vez de fallar limpio al conectar.
	//Sube a "6": BrowseActionMessage.Action gana DELETE y CREATE. Añadir al FINAL de un enum no renumera
	//nada, pero un cliente nuevo mandandole ese ordinal a un servidor viejo revienta al leerlo — que es
	//exactamente lo que el handshake debe impedir. DELETE se colo sin subir la version: por eso existe
	//ahora NETWORK_SHAPE, que hace fallar el build cuando la forma de la red cambia y esta linea no.
	//Sube a "8": BrowseListMessage manda sus etiquetas como Component y ya no como texto plano, para que el
	//compendio lo traduzca el CLIENTE en su idioma en vez de que el servidor le fije el suyo. Cambia el
	//formato en el cable sin cambiar ni el numero de mensajes ni su orden — o sea que NI NETWORK_SHAPE ni
	//NETWORK_ORDER lo ven, y por eso existe ahora tambien NETWORK_WIRE (ver abajo).
	//Sube a "16": se registran 7 mensajes nuevos al FINAL (DungeonTraceCaptureMessage,
	//MonsterSpawnListRequestMessage, MonsterSpawnListMessage, SpellGiveListRequestMessage,
	//SpellGiveListMessage, WeaponGiveListRequestMessage, WeaponGiveListMessage), y MonsterBindMessage y
	//WildShapeMessage ganan campos nuevos en el cable (bestiario resuelto en servidor).
	//Sube a "17": el toolkit de mazmorras se muda a su propio addon/mod (dndsheets_dungeon) con su
	//propio canal — SALEN 10 mensajes de este canal (DungeonGenerateMessage,
	//DungeonJigsawConfigureMessage, DungeonJigsawConfigureOpenMessage, DungeonPieceAddOpenMessage,
	//DungeonPieceCaptureMessage, DungeonPieceListMessage, DungeonPieceListRequestMessage,
	//DungeonPieceRemoveMessage, DungeonPieceUpdateMessage, DungeonTraceCaptureMessage). Un cliente
	//viejo que aun los mande a este canal no encuentra receptor: correcto, porque ese addon ya no
	//escucha aqui, escucha en su propio canal "dndsheets_dungeon".
	//Sube a "18": nuevo mensaje StaffBindMessage (báculos de hechizo reconfigurables desde el Grimorio).
	//Sube a "19": TurnStateMessage gana el tablero de iniciativa completo (roster con condiciones/estado
	//por combatiente), para el HUD de turnos profesional.
	//Sube a "20": RosterRow gana bonusActionUsed (acción adicional separada de la acción, ver TurnManager).
	//Sube a "21": botón "Multiclasear" en la ficha (F25 del audit). Se registra MulticlassMessage al
	//final, y PresetListRequestMessage/PresetListMessage ganan un campo "multiclass" en el cable (mismo
	//viaje de ida y vuelta, ahora con un flag más) para que PresetScreen sepa en qué modo abrirse.
	//Sube a "22": tres cosas en un mismo lote. (1) RosterRow gana currentHp/maxHp al final del payload
	//(barra de vida en el tablero de turnos y en el nombre flotante). (2) BrowseListMessage gana el
	//campo context al final. (3) La renumeración deliberada: las 8 parejas List/ListRequest (16 clases,
	//incluida la pareja CharacterOptions* que ya no mandaba nadie) se funden como acciones/kinds nuevos
	//de BrowseActionMessage/BrowseListMessage y sus registros se borran — todos los ids posteriores
	//cambian, que es exactamente lo que este handshake convierte en un fallo limpio de conexión.
	private static final String PROTOCOL_VERSION = "22";

	/**
	 * <p>Cuántas piezas cruzan el cable: mensajes registrados más constantes de los enums que viajan por
	 * ordinal. <b>No lo usa el juego</b>: existe para que {@code JsonContentSelfTest} pueda comparar contra
	 * la forma real y tumbar el build cuando alguien añade una y no toca {@link #PROTOCOL_VERSION}.</p>
	 *
	 * <p>Las invariantes 1 y 2 de PROJECT_CONTEXT.md son las dos que más veces han costado una sesión de
	 * depuración, y las dos fallan en silencio: nada se rompe al compilar, y el cliente y el servidor se dan
	 * la mano igual para desalinearse después. Un número que hay que tocar a mano no impide el error, pero
	 * lo convierte en una decisión en vez de un olvido.</p>
	 */
	public static final int NETWORK_SHAPE = 112;

	/**
	 * <p>El orden exacto en que las piezas cruzan el cable, resumido en un hash. {@link #NETWORK_SHAPE}
	 * cuenta cuántas hay, y por eso no ve el fallo que la invariante 1 nombra primero: <b>reordenar</b> dos
	 * entradas ya registradas no cambia la cuenta. Borrar baja el número, insertar en medio lo sube, pero
	 * intercambiar dos deja {@link #NETWORK_SHAPE} igual — y los ids se renumeran en silencio.</p>
	 *
	 * <p>Tampoco lo usa el juego: {@code JsonContentSelfTest.checkNetworkShape} rehace el hash desde la
	 * fuente y tumba el build cuando no cuadra. Si mueves algo a propósito, sube {@link #PROTOCOL_VERSION}
	 * y pega aquí el número que te diga el fallo.</p>
	 */
	public static final int NETWORK_ORDER = 1366669976;

	/**
	 * <p>Que se escribe y se lee en el cable, resumido en un hash: la secuencia de llamadas
	 * {@code writeX}/{@code readX} de cada clase de {@code network/}.</p>
	 *
	 * <p>Es el tercer angulo del mismo problema, y el que faltaba. {@link #NETWORK_SHAPE} cuenta CUANTAS
	 * piezas cruzan; {@link #NETWORK_ORDER} vigila EN QUE ORDEN; ninguno de los dos ve que un campo cambie
	 * de TIPO. Pasar {@code BrowseListMessage.labels} de {@code writeUtf} a {@code writeComponent} dejo los
	 * dos numeros intactos y aun asi rompio la compatibilidad: un cliente viejo leería un texto donde el
	 * servidor nuevo escribe un Component, y se desincroniza a mitad del paquete.</p>
	 */
	public static final int NETWORK_WIRE = -2020662340;
	public static final SimpleChannel PACKET_HANDLER = NetworkRegistry.newSimpleChannel(new ResourceLocation(MODID, MODID), () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);
	private static int messageID = 0;

	public static <T> void addNetworkMessage(Class<T> messageType, BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder, BiConsumer<T, Supplier<NetworkEvent.Context>> messageConsumer) {
		PACKET_HANDLER.registerMessage(messageID, messageType, encoder, decoder, messageConsumer);
		messageID++;
	}

	//Registro centralizado de las 60+ clases de network/: antes cada una se autorregistraba con su propio
	//@Mod.EventBusSubscriber + método registerMessage(FMLCommonSetupEvent) — ~5 líneas de boilerplate
	//idéntico repetidas por clase. SimpleChannel exige que cada clase siga teniendo su propio buffer/
	//constructor/handler (no se puede genericar eso), pero el PUNTO donde se registran sí es uno solo.
	private static void registerNetworkMessages(FMLCommonSetupEvent event) {
		addNetworkMessage(AddCustomAttackMessage.class, AddCustomAttackMessage::buffer, AddCustomAttackMessage::new, AddCustomAttackMessage::handler);
		addNetworkMessage(AdvancedRollEditorOpenMessage.class, AdvancedRollEditorOpenMessage::buffer, AdvancedRollEditorOpenMessage::new, AdvancedRollEditorOpenMessage::handler);
		addNetworkMessage(CharacterSheetOpenMessage.class, CharacterSheetOpenMessage::buffer, CharacterSheetOpenMessage::new, CharacterSheetOpenMessage::handler);
		addNetworkMessage(ClearCustomAttacksMessage.class, ClearCustomAttacksMessage::buffer, ClearCustomAttacksMessage::new, ClearCustomAttacksMessage::handler);
		addNetworkMessage(ContentEntryRemoveMessage.class, ContentEntryRemoveMessage::buffer, ContentEntryRemoveMessage::new, ContentEntryRemoveMessage::handler);
		addNetworkMessage(ContentEntrySaveMessage.class, ContentEntrySaveMessage::buffer, ContentEntrySaveMessage::new, ContentEntrySaveMessage::handler);
		addNetworkMessage(DeathSaveGiveUpMessage.class, DeathSaveGiveUpMessage::buffer, DeathSaveGiveUpMessage::new, DeathSaveGiveUpMessage::handler);
		addNetworkMessage(DeathSaveRollMessage.class, DeathSaveRollMessage::buffer, DeathSaveRollMessage::new, DeathSaveRollMessage::handler);
		addNetworkMessage(MonsterActionChooseMessage.class, MonsterActionChooseMessage::buffer, MonsterActionChooseMessage::new, MonsterActionChooseMessage::handler);
		addNetworkMessage(GiveItemMessage.class, GiveItemMessage::buffer, GiveItemMessage::new, GiveItemMessage::handler);
		addNetworkMessage(MonsterActionOpenMessage.class, MonsterActionOpenMessage::buffer, MonsterActionOpenMessage::new, MonsterActionOpenMessage::handler);
		addNetworkMessage(MonsterSaveTemplateMessage.class, MonsterSaveTemplateMessage::buffer, MonsterSaveTemplateMessage::new, MonsterSaveTemplateMessage::handler);
		addNetworkMessage(MonsterSpawnMessage.class, MonsterSpawnMessage::buffer, MonsterSpawnMessage::new, MonsterSpawnMessage::handler);
		addNetworkMessage(OptionsSaveMessage.class, OptionsSaveMessage::buffer, OptionsSaveMessage::new, OptionsSaveMessage::handler);
		addNetworkMessage(PassivePerceptionRequestMessage.class, PassivePerceptionRequestMessage::buffer, PassivePerceptionRequestMessage::new, PassivePerceptionRequestMessage::handler);
		addNetworkMessage(PresetApplyMessage.class, PresetApplyMessage::buffer, PresetApplyMessage::new, PresetApplyMessage::handler);
		addNetworkMessage(PresetApplyToMessage.class, PresetApplyToMessage::buffer, PresetApplyToMessage::new, PresetApplyToMessage::handler);
		addNetworkMessage(RemoveCustomAttackMessage.class, RemoveCustomAttackMessage::buffer, RemoveCustomAttackMessage::new, RemoveCustomAttackMessage::handler);
		addNetworkMessage(RestProposeMessage.class, RestProposeMessage::buffer, RestProposeMessage::new, RestProposeMessage::handler);
		addNetworkMessage(RestVoteCloseMessage.class, RestVoteCloseMessage::buffer, RestVoteCloseMessage::new, RestVoteCloseMessage::handler);
		addNetworkMessage(RestVoteOpenMessage.class, RestVoteOpenMessage::buffer, RestVoteOpenMessage::new, RestVoteOpenMessage::handler);
		addNetworkMessage(RestVoteResponseMessage.class, RestVoteResponseMessage::buffer, RestVoteResponseMessage::new, RestVoteResponseMessage::handler);
		addNetworkMessage(RollEditorOpenMessage.class, RollEditorOpenMessage::buffer, RollEditorOpenMessage::new, RollEditorOpenMessage::handler);
		addNetworkMessage(ScreenActionMessage.class, ScreenActionMessage::buffer, ScreenActionMessage::new, ScreenActionMessage::handler);
		addNetworkMessage(SheetAdjustMessage.class, SheetAdjustMessage::buffer, SheetAdjustMessage::new, SheetAdjustMessage::handler);
		addNetworkMessage(SheetClientMessage.class, SheetClientMessage::buffer, SheetClientMessage::new, SheetClientMessage::handler);
		addNetworkMessage(SheetFieldUpdateMessage.class, SheetFieldUpdateMessage::buffer, SheetFieldUpdateMessage::new, SheetFieldUpdateMessage::handler);
		addNetworkMessage(SheetRollButtonMessage.class, SheetRollButtonMessage::buffer, SheetRollButtonMessage::new, SheetRollButtonMessage::handler);
		addNetworkMessage(SheetServerMessage.class, SheetServerMessage::buffer, SheetServerMessage::new, SheetServerMessage::handler);
		addNetworkMessage(SheetSummaryMessage.class, SheetSummaryMessage::buffer, SheetSummaryMessage::new, SheetSummaryMessage::handler);
		addNetworkMessage(SheetSummaryRequestMessage.class, SheetSummaryRequestMessage::buffer, SheetSummaryRequestMessage::new, SheetSummaryRequestMessage::handler);
		addNetworkMessage(SpawnGenericMessage.class, SpawnGenericMessage::buffer, SpawnGenericMessage::new, SpawnGenericMessage::handler);
		addNetworkMessage(SpellCastMessage.class, SpellCastMessage::buffer, SpellCastMessage::new, SpellCastMessage::handler);
		addNetworkMessage(net.hawthorn.dndsheets.network.TurnActionMessage.class, net.hawthorn.dndsheets.network.TurnActionMessage::buffer,
			net.hawthorn.dndsheets.network.TurnActionMessage::new, net.hawthorn.dndsheets.network.TurnActionMessage::handler);
		addNetworkMessage(net.hawthorn.dndsheets.network.AbilityImprovementMessage.class, net.hawthorn.dndsheets.network.AbilityImprovementMessage::buffer,
			net.hawthorn.dndsheets.network.AbilityImprovementMessage::new, net.hawthorn.dndsheets.network.AbilityImprovementMessage::handler);
		addNetworkMessage(SpellGiveMessage.class, SpellGiveMessage::buffer, SpellGiveMessage::new, SpellGiveMessage::handler);
		addNetworkMessage(TraitGrantMessage.class, TraitGrantMessage::buffer, TraitGrantMessage::new, TraitGrantMessage::handler);
		addNetworkMessage(TurnControlMessage.class, TurnControlMessage::buffer, TurnControlMessage::new, TurnControlMessage::handler);
		addNetworkMessage(TurnEffectApplyMessage.class, TurnEffectApplyMessage::buffer, TurnEffectApplyMessage::new, TurnEffectApplyMessage::handler);
		addNetworkMessage(TurnStateMessage.class, TurnStateMessage::buffer, TurnStateMessage::new, TurnStateMessage::handler);
		addNetworkMessage(TutorialOpenMessage.class, TutorialOpenMessage::buffer, TutorialOpenMessage::new, TutorialOpenMessage::handler);
		addNetworkMessage(WeaponGiveMessage.class, WeaponGiveMessage::buffer, WeaponGiveMessage::new, WeaponGiveMessage::handler);

		//A PARTIR DE AQUÍ, POR ORDEN DE INCORPORACIÓN, NO ALFABÉTICO. El id de red de cada mensaje es su
		//orden de registro, así que meter uno nuevo en su hueco alfabético (Roster... iría entre Rest... y
		//Sheet...) renumeraría en silencio todos los de después. Añade siempre al final de esta lista.
		//(Los ids ya se renumeraron UNA vez, a propósito y con PROTOCOL_VERSION subida, cuando las 8
		//parejas List/ListRequest se fundieron en BrowseAction/BrowseList y sus 16 clases se borraron.)
		addNetworkMessage(BrowseActionMessage.class, BrowseActionMessage::buffer, BrowseActionMessage::new, BrowseActionMessage::handler);
		addNetworkMessage(BrowseListMessage.class, BrowseListMessage::buffer, BrowseListMessage::new, BrowseListMessage::handler);
		addNetworkMessage(MonsterBindMessage.class, MonsterBindMessage::buffer, MonsterBindMessage::new, MonsterBindMessage::handler);
		addNetworkMessage(WildShapeMessage.class, WildShapeMessage::buffer, WildShapeMessage::new, WildShapeMessage::handler);
		addNetworkMessage(StaffBindMessage.class, StaffBindMessage::buffer, StaffBindMessage::new, StaffBindMessage::handler);
		addNetworkMessage(MulticlassMessage.class, MulticlassMessage::buffer, MulticlassMessage::new, MulticlassMessage::handler);
	}

	/**
	 * <p>Único punto donde el mod pregunta "¿puede esta fuente actuar como DM?" — operador de verdad, O modo
	 * solo encendido ({@link Config#soloMode()}). Reemplaza cada {@code hasPermission(2)}/
	 * {@code hasPermissions(2)} suelto del mod: en modo solo no hay jerarquía nueva que inventar (ni "líder
	 * de grupo", ni permisos por acción) — todos los jugadores conectados quedan igual de confiables entre
	 * sí, el mismo nivel de confianza que ya hace falta para compartir el mismo mundo. La única excepción
	 * real es {@code SheetServerMessage} (checks/saves/skills de la propia hoja), que no es un gate de "sos
	 * DM" sino de integridad de datos ya calculados solos, y no pasa por aquí.</p>
	 */
	public static boolean canActAsDm(CommandSourceStack source) {
		return source.hasPermission(2) || Config.soloMode();
	}

	/** Misma pregunta que {@link #canActAsDm(CommandSourceStack)}, para los call sites que solo tienen la entidad a mano. */
	public static boolean canActAsDm(Entity entity) {
		return entity.hasPermissions(2) || Config.soloMode();
	}

	//Patrón repetido en los mensajes cliente(DM)->servidor que actúan sobre OTRO jugador (SheetAdjustMessage,
	//TraitGrantMessage, PresetApplyToMessage): comprobar que quien envía el mensaje es un operador (o que el
	//modo solo está encendido, ver canActAsDm) y que el jugador objetivo sigue conectado, antes de delegar.
	//Llamar dentro de context.enqueueWork(...).
	public static void withDmTarget(NetworkEvent.Context context, String targetUuid, Consumer<ServerPlayer> action) {
		ServerPlayer dm = context.getSender();
		if (dm == null || !canActAsDm(dm)) return;
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
	//conjuro, que antes reenviaban toda la hoja en cada golpe/hechizo.
	public static void sendSheetFieldUpdate(ServerPlayer player, JsonObject patch) {
		PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetFieldUpdateMessage(patch.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	//Mutable a propósito (ticksLeft se decrementa in place cada tick): un record forzaría reconstruir/
	//reemplazar la entrada en la cola en cada tick solo para restar 1.
	private static final class PendingWork {
		final Runnable action;
		int ticksLeft;

		PendingWork(Runnable action, int ticksLeft) {
			this.action = action;
			this.ticksLeft = ticksLeft;
		}
	}

	private static final Collection<PendingWork> workQueue = new ConcurrentLinkedQueue<>();

	public static void queueServerWork(int tick, Runnable action) {
		workQueue.add(new PendingWork(action, tick));
	}

	@SubscribeEvent
	public void tick(TickEvent.ServerTickEvent event) {
		if (event.phase != TickEvent.Phase.END || workQueue.isEmpty()) return;

		Iterator<PendingWork> it = workQueue.iterator();
		while (it.hasNext()) {
			PendingWork work = it.next();
			if (--work.ticksLeft <= 0) {
				work.action.run();
				it.remove();
			}
		}
	}
}
