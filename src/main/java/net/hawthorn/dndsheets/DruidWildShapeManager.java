package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Forma Salvaje del druida: el jugador <b>se convierte</b> en una bestia del bestiario. Toma sus PG,
 * su CA, sus características físicas y su ataque, y se ve como ella. Al terminar —por tiempo, a voluntad,
 * o porque la forma cayó a 0 PG— vuelve a ser él, con la vida que tenía antes de transformarse.</p>
 *
 * <p><b>Dónde vive el estado, y por qué importa.</b> En la <b>hoja</b>, no en memoria: la hoja se persiste
 * y se sincroniza sola, así que desconectarse a mitad de la forma —o que se caiga el servidor— no deja a
 * nadie con las características de un oso para siempre. Es la misma razón por la que las condiciones se
 * guardan donde se guardan (ver {@code Combatant}).</p>
 *
 * <p><b>Por qué no hay un {@code WildShapeCombatant}.</b> Era la opción evidente y es la equivocada. Todo
 * lo que pregunta "¿cuál es su CA?", "¿cuántos PG le quedan?" o "¿cuál es su modificador de Fuerza?" ya
 * pasa por la hoja y por el atributo de vida de Minecraft. Así que la transformación <b>escribe ahí</b>
 * los números de la bestia y guarda los de antes para devolverlos: el motor entero —el monstruo que
 * decide si acierta, la lista de grupo del DM, el HUD, la concentración— ve la forma sin que ninguno de
 * ellos sepa que existe. Un combatiente nuevo habría obligado a que cada uno de esos caminos supiera
 * distinguirlo.</p>
 *
 * <p><b>Lo que no hace</b>, y es 5e de verdad: no conserva las competencias de salvación de la bestia, y
 * no limita por VD (el bestiario no trae el dato). El DM decide qué bestia vale, que es como se juega en
 * una mesa.</p>
 */
@Mod.EventBusSubscriber
public class DruidWildShapeManager {
	private static final int DURATION_ROUNDS = 10; //1 hora de 5e simplificada a 10 asaltos, igual que la Furia.
	private static final int DURATION_TICKS = 20 * 60;

	/** Golpe por defecto si la bestia elegida no declara ningún ataque. */
	private static final String FALLBACK_DICE = "1d6";
	private static final String FALLBACK_ABILITY = "str";

	//Lo de la bestia se ESCRIBE en la hoja, así que hay que guardar lo del druida para devolverlo. Todo
	//bajo el mismo prefijo: si algún día algo sale mal, se ve de un vistazo qué dejó puesto la forma.
	private static final String SHAPE_ID = "wildShapeId";
	private static final String RETURN_HP = "wildShapeReturnHp";
	private static final String OLD_AC = "wildShapeOldAc";
	private static final String OLD_ABILITY = "wildShapeOld_";

	/** Las que cambia la forma. Int/Sab/Car se quedan: en 5e la bestia no te vuelve tonto. */
	private static final List<String> PHYSICAL = List.of("str", "dex", "con");
	private static final Map<String, String> LONG = Map.of(
		"str", "strength", "dex", "dexterity", "con", "constitution");

	/** El id de la bestia en la que está, o {@code null} si no está transformado. */
	public static String shapeOf(JsonObject sheet) {
		if (sheet == null || !sheet.has(SHAPE_ID)) return null;
		String id = sheet.get(SHAPE_ID).getAsString();
		return id.isEmpty() ? null : id;
	}

	public static boolean isShifted(ServerPlayer player) {
		return shapeOf(SheetLoader.getServerSheet(player.getStringUUID())) != null;
	}

	/** Devuelve al druida a su forma sin avisar: la usa el cambio de personaje. Ver SheetLoader. */
	public static void clearFor(ServerPlayer player) {
		revert(player, false);
	}

	/**
	 * <p>El zarpazo: los dados del primer ataque de la bestia en la que está, que es lo que 5e llama
	 * "usas las estadísticas de la bestia". Sin ataque declarado, un 1d6 por Fuerza — el mismo de antes,
	 * para que una bestia a medio escribir siga siendo jugable.</p>
	 */
	public static TraitRegistry.UnarmedProfile unarmedProfile(ServerPlayer player) {
		MonsterRegistry.MonsterStatBlock block = blockOf(SheetLoader.getServerSheet(player.getStringUUID()));
		if (block == null || block.attacks().isEmpty()) {
			return new TraitRegistry.UnarmedProfile(FALLBACK_DICE, FALLBACK_ABILITY);
		}
		MonsterRegistry.MonsterAttack attack = block.attacks().get(0);
		return new TraitRegistry.UnarmedProfile(attack.dice(), attack.damageAbility());
	}

	private static MonsterRegistry.MonsterStatBlock blockOf(JsonObject sheet) {
		String id = shapeOf(sheet);
		return id == null ? null : MonsterRegistry.get(id);
	}

	/** Las bestias que se pueden elegir: el bestiario filtrado por tipo, sin lista aparte que mantener. */
	public static List<String> beastIds() {
		List<String> beasts = new java.util.ArrayList<>();
		for (String id : MonsterRegistry.ids()) {
			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(id);
			if (block != null && block.type() == CreatureType.BEAST) beasts.add(id);
		}
		return beasts;
	}

	public static void activate(ServerPlayer player, String monsterId) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		if (shapeOf(sheet) != null) {
			//Ya transformado: el segundo clic deshace, que es lo que se espera de un botón de alternar y
			//evita tener que acordarse de un comando para volver.
			revert(player, true);
			return;
		}

		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(monsterId);
		if (block == null || block.type() != CreatureType.BEAST) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.wildshape.not_a_beast").withStyle(ChatFormatting.GRAY));
			return;
		}

		//La vida a la que vuelve es la que tiene AHORA, antes de tocar nada. En 5e vuelves con los PG que
		//tenías al transformarte, y el daño que se llevó la bestia se queda con la bestia.
		writeShape(sheet, block, (int) Math.ceil(player.getHealth()));
		setMaxHealth(player, block.maxHp());
		player.setHealth(block.maxHp());
		SheetLoader.saveAndSync(player, sheet);
		WildShapeWatcher.broadcast(player, monsterId);
		CombatFx.activate(player);

		UUID uuid = player.getUUID();
		MinecraftServer server = player.getServer();
		TurnManager.scheduleExpiry(DURATION_ROUNDS, DURATION_TICKS, () -> {
			//Se reengancha por UUID en vez de capturar al jugador: puede haberse desconectado durante los
			//10 asaltos, y entonces no hay a quién devolver nada (la hoja lo espera para su próxima entrada).
			ServerPlayer stillHere = server == null ? null : server.getPlayerList().getPlayer(uuid);
			if (stillHere != null) revert(stillHere, true);
		});

		player.sendSystemMessage(Component.translatable("chat.dndsheets.wildshape.start", block.name()).withStyle(ChatFeedback.RESOURCE));
	}

	/**
	 * <p>Deshace la transformación y devuelve al druida lo que era. {@code announce} en falso para el
	 * camino silencioso (cambio de personaje): ahí no hay nada que anunciar porque ya no es ese personaje.</p>
	 */
	public static void revert(ServerPlayer player, boolean announce) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null || shapeOf(sheet) == null) return;

		int back = clearShape(sheet, (int) Math.ceil(player.getMaxHealth()));

		//El máximo se recalcula desde la clase y el nivel en vez de guardarse: es la misma cuenta que hace
		//SheetLoader al entrar al mundo, y guardar un número que ya sabe derivar sería una segunda verdad.
		SheetLoader.applyClassHitPoints(player, sheet);
		//Nunca por debajo de 1: la forma que cae no mata al druida, lo devuelve (5e). Quien lo quiera
		//muerto tendrá que volver a bajarlo, ya en su cuerpo.
		player.setHealth(Math.max(1f, Math.min(player.getMaxHealth(), back)));

		SheetLoader.saveAndSync(player, sheet);
		WildShapeWatcher.broadcast(player, "");
		if (announce) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.wildshape.end").withStyle(ChatFormatting.GRAY));
		}
	}

	/**
	 * <p>La forma cae a 0: el druida vuelve, no muere. Va en {@code HIGHEST} y cancela el evento para
	 * llegar <b>antes</b> que {@code DeathSaveManager.onLivingDeath}, que es quien lo tumbaría a
	 * salvaciones de muerte — un druida que sale de la forma no está caído, está en su cuerpo otra vez.</p>
	 */
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onShapeDropped(LivingDeathEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		if (!isShifted(player)) return;
		event.setCanceled(true);
		revert(player, true);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.wildshape.dropped").withStyle(ChatFormatting.GRAY));
	}

	/**
	 * <p>Escribe en la hoja los números de la bestia y guarda debajo los del druida. Puro y sin jugador
	 * delante a propósito: es la parte que puede dejar una ficha corrupta para siempre —un druida con la
	 * Fuerza de un oso y sin nada que se la devuelva— y {@code JsonContentSelfTest} corre sin juego.</p>
	 */
	static void writeShape(JsonObject sheet, MonsterRegistry.MonsterStatBlock block, int returnHp) {
		sheet.addProperty(SHAPE_ID, block.id());
		sheet.addProperty(RETURN_HP, returnHp);

		//Guardar que NO había override es tan importante como guardar cuál era: sin esta rama, un druida
		//sin CA fijada volvería con la de la bestia puesta a mano para siempre.
		if (sheet.has("armorClassOverride")) sheet.addProperty(OLD_AC, sheet.get("armorClassOverride").getAsInt());
		else sheet.remove(OLD_AC);
		sheet.addProperty("armorClassOverride", block.ac());

		for (String ability : PHYSICAL) {
			String key = LONG.get(ability);
			sheet.addProperty(OLD_ABILITY + ability, sheet.has(key) ? sheet.get(key).getAsString() : "10");
			sheet.addProperty(key, String.valueOf(block.abilities().getOrDefault(ability, 10)));
		}
	}

	/** Deshace {@link #writeShape} y devuelve los PG a los que vuelve el druida. */
	static int clearShape(JsonObject sheet, int fallbackHp) {
		for (String ability : PHYSICAL) {
			String key = LONG.get(ability);
			if (sheet.has(OLD_ABILITY + ability)) sheet.addProperty(key, sheet.get(OLD_ABILITY + ability).getAsString());
			sheet.remove(OLD_ABILITY + ability);
		}
		if (sheet.has(OLD_AC)) sheet.addProperty("armorClassOverride", sheet.get(OLD_AC).getAsInt());
		else sheet.remove("armorClassOverride");
		sheet.remove(OLD_AC);
		sheet.remove(SHAPE_ID);

		int back = sheet.has(RETURN_HP) ? sheet.get(RETURN_HP).getAsInt() : fallbackHp;
		sheet.remove(RETURN_HP);
		return back;
	}

	private static void setMaxHealth(ServerPlayer player, int value) {
		AttributeInstance attribute = player.getAttribute(Attributes.MAX_HEALTH);
		if (attribute != null) attribute.setBaseValue(value);
	}

	//--- Ítem de Forma Salvaje: se activa desde AbilityItemDispatcher en vez de suscribirse a los 3 eventos
	//de interacción por separado. Mismo patrón que el Tótem de Furia
	//(BarbarianRageManager). ---

	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		//Transformado ya: el clic deshace, sin abrir nada. Sin transformar: se elige bestia.
		if (isShifted(player)) {
			revert(player, true);
			return;
		}
		WildShapeWatcher.openPicker(player);
	}

	public static ItemStack buildWildShapeStack() {
		return AbilityItem.build(ItemLook.WILD_SHAPE, "wildShape", Component.translatable("chat.dndsheets.wildshape.item_name"),
			Component.translatable("chat.dndsheets.wildshape.item_lore", DURATION_ROUNDS).withStyle(ChatFormatting.GRAY));
	}
}
