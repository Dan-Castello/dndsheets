package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>Expulsar Muertos Vivientes del clérigo (Canalizar Divinidad): todo no-muerto a {@value #RADIUS}
 * bloques tira una salvación de Sabiduría contra la CD de conjuro del clérigo, y el que falla queda
 * <b>asustado</b> durante {@value #DURATION_ROUNDS} asaltos. Una vez por descanso, corto o largo.</p>
 *
 * <p>El clérigo era la <b>única</b> clase con preset y sin ningún recurso propio: bárbaro, bardo, druida,
 * guerrero, hechicero, explorador, mago, monje y paladín tenían el suyo, y quien elegía clérigo se
 * encontraba un lanzador de conjuros a secas. Este es su botón.</p>
 *
 * <p>No se pudo escribir hasta que los monstruos tuvieron tipo de criatura ({@link CreatureType}): un
 * "expulsar muertos vivientes" que no sabe distinguir un muerto viviente es un empujón a todo el mundo.</p>
 *
 * <p><b>Lo que no está</b>: Destruir Muertos Vivientes, la mejora de nivel 5 que fulmina en el acto a los
 * de VD baja. Un bloque de estadísticas de este mod no tiene VD, y sustituirla por los PG haría desaparecer
 * a un no-muerto legendario de pocos PG y sobrevivir a uno flojo con muchos: el umbral estaría midiendo
 * otra cosa. Requiere un campo nuevo, no una aproximación.</p>
 */
public class ClericTurnUndeadManager {
	/** 30 pies de 5e, a un bloque por cada 5 pies. */
	private static final int RADIUS = 6;
	/** 1 minuto de 5e = 10 asaltos, igual que la Furia. */
	private static final int DURATION_ROUNDS = 10;
	private static final String CONDITION = "asustado";

	//Mismo "usado/no usado" que Segundo Aliento, y por lo mismo: Canalizar Divinidad no tiene duración que
	//contar, solo se gasta y se recupera al descansar. En la hoja, para que sea del personaje y sobreviva a
	//un reinicio — ver RestResource.

	public static void use(ServerPlayer cleric) {
		if (!RestResource.spend(cleric, RestResource.CHANNEL_DIVINITY)) {
			cleric.sendSystemMessage(Component.translatable("chat.dndsheets.resource.spent_channel").withStyle(ChatFormatting.GRAY));
			return;
		}

		JsonObject sheet = SheetLoader.getServerSheet(cleric.getStringUUID());
		if (sheet == null) return;

		//Misma CD que cualquier conjuro suyo (8 + competencia + Sabiduría): Expulsar es una capacidad de
		//clérigo, no un efecto aparte con sus propios números.
		int proficiency = CharacterRules.proficiencyBonusFor(SheetLoader.characterLevelOf(sheet, cleric));
		int saveDc = 8 + proficiency + CombatManager.abilityModifier(sheet, "wisdom");
		String clericName = SheetLoader.characterNameOf(sheet, cleric);

		CombatFx.activate(cleric);
		ChatFeedback.broadcast(cleric, Component.literal(clericName + " expulsa a los muertos vivientes (CD " + saveDc + ").").withStyle(ChatFeedback.RESOURCE));

		int turned = 0;
		AABB box = new AABB(cleric.position(), cleric.position()).inflate(RADIUS);
		for (Entity target : cleric.level().getEntities(cleric, box, entity -> entity.isAlive()
				&& MonsterRegistry.typeOf(entity) == CreatureType.UNDEAD)) {
			if (turnOne(cleric, target, saveDc)) turned++;
		}

		if (turned == 0) {
			//Se avisa aunque no haya funcionado nada: sin esto, gastar el recurso sin ningún no-muerto cerca
			//se ve exactamente igual que un uso que salió mal, y el clérigo no sabe cuál de las dos fue.
			cleric.sendSystemMessage(Component.translatable("chat.dndsheets.resource.turn_undead_none").withStyle(ChatFormatting.GRAY));
		}
	}

	/** @return true si de verdad quedó expulsado. */
	private static boolean turnOne(ServerPlayer cleric, Entity target, int saveDc) {
		Combatant combatant = Combatant.of(target);
		//Sin bloque de estadísticas no hay Sabiduría que tirar. No puede pasar hoy (typeOf solo devuelve
		//no-muerto para un monstruo del mod), pero dejarlo sin comprobar convierte un cambio futuro en un
		//NullPointerException dentro de un bucle.
		if (combatant == null) return false;

		Combatant.SaveRoll save = combatant.rollSave("wis");
		String targetName = MonsterRegistry.statBlockOf(target) != null
			? MonsterRegistry.statBlockOf(target).name() : target.getName().getString();

		if (save.succeeds(saveDc)) {
			ChatFeedback.broadcast(cleric, Component.literal(targetName + " resiste: " + save.formatted() + " vs CD " + saveDc + ".").withStyle(ChatFormatting.GRAY));
			return false;
		}

		//El clérigo va como fuente porque "asustado" en 5e depende de QUIÉN te asusta: no puedes acercarte a
		//él, y tienes desventaja mientras lo veas (ver TurnManager.applyEffect y Condition).
		TurnManager.applyEffect(target, CONDITION, "0", DURATION_ROUNDS, cleric);
		CombatFx.spellImpact(target, false, "radiante");
		ChatFeedback.broadcast(cleric, Component.literal(targetName + " es expulsado: " + save.formatted() + " vs CD " + saveDc + ".").withStyle(ChatFormatting.GOLD));
		return true;
	}

	//Público: RestManager lo llama para los dos tipos de descanso — en 5e Canalizar Divinidad se recupera
	//con un descanso corto, igual que Segundo Aliento.
	public static void resetOnRest(ServerPlayer player) {
		RestResource.restore(player, RestResource.CHANNEL_DIVINITY);
	}

	//Se activa desde AbilityItemDispatcher en vez de suscribirse a los eventos de interacción por su cuenta
	//. Mismo patrón que el resto de ítems de capacidad.
	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (event.getEntity() instanceof ServerPlayer player) use(player);
	}

	public static ItemStack buildTurnUndeadStack() {
		return AbilityItem.build(ItemLook.TURN_UNDEAD, "turnUndead", Component.translatable("chat.dndsheets.turn_undead.item_name"),
			Component.translatable("chat.dndsheets.turn_undead.item_lore").withStyle(ChatFormatting.GRAY));
	}
}
