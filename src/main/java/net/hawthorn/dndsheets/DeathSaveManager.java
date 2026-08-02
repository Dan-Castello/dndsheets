package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.network.DeathSaveCloseMessage;
import net.hawthorn.dndsheets.network.DeathSaveOpenMessage;
import net.hawthorn.dndsheets.network.SheetClientMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * <p>Cuando un jugador llegaría a 0 PG, se cancela su muerte real: se congela en 1 PG, incapacitado
 * (ciego, débil y casi inmóvil), y se le abre una ventana para tirar sus 3 salvaciones de muerte. Otro
 * jugador puede reanimarlo al instante interactuando con él. 3 éxitos (o un 20 natural) lo estabiliza;
 * 3 fallos (o un 1 natural cuenta doble) sí lo mata de verdad.</p>
 */
@Mod.EventBusSubscriber
public class DeathSaveManager {
	private static final int INFINITE_DURATION = 1_000_000;
	private static final Set<UUID> allowRealDeath = ConcurrentHashMap.newKeySet();

	//Se cancela la muerte real y se pasa al estado "caído" en su lugar, salvo que ya se le haya dejado
	//morir de verdad (3 fallos de salvación) o ya esté caído (para no reiniciar el conteo por accidente).
	@SubscribeEvent
	public static void onLivingDeath(LivingDeathEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		if (allowRealDeath.remove(player.getUUID())) {
			//Muerte real de verdad (3 fallos de salvación): antes de esto la hoja se quedaba con
			//downed=true para siempre. El jugador reaparecía con vida llena pero
			//onAttackWhileDowned/onLivingHurtWhileDowned seguían tratándolo como caído (no podía atacar
			//ni recibir daño nunca más) y resendStateOnJoin le reabría la pantalla de salvaciones en
			//cada reconexión. Se limpia igual que stabilize(), porque esto ES el fin del estado "caído".
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			if (sheet != null) {
				sheet.addProperty("downed", false);
				sheet.addProperty("deathSaveSuccesses", 0);
				sheet.addProperty("deathSaveFailures", 0);
				sendSheetUpdate(player, sheet);
			}
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new DeathSaveCloseMessage());
			return;
		}

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null || isDowned(sheet)) return;

		event.setCanceled(true);
		goDown(player, sheet);
	}

	//ponytail: protege al jugador caído con invulnerabilidad total en vez de tratar el daño extra como
	//fallo automático de salvación (regla real de 5e). Más simple y evita que lo rematen sin querer;
	//si se quiere ese matiz, añadirlo aquí.
	@SubscribeEvent(priority = EventPriority.HIGH)
	public static void onLivingHurtWhileDowned(LivingHurtEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!(event.getEntity() instanceof Player victim)) return;
		//El golpe de gracia por 3 fallos de salvación (ver handleRollRequest) también pasa por acá antes de
		//llegar a LivingDeathEvent: si se cancelara igual que cualquier otro daño mientras está caído, el PG
		//nunca bajaba de 1 y el jugador se quedaba caído para siempre sin morir de verdad. allowRealDeath
		//es la misma marca que ya usa onLivingDeath para no cancelar ESE evento en particular.
		if (allowRealDeath.contains(victim.getUUID())) return;
		JsonObject sheet = SheetLoader.getServerSheet(victim.getStringUUID());
		if (sheet != null && isDowned(sheet)) event.setCanceled(true);
	}

	@SubscribeEvent(priority = EventPriority.HIGH)
	public static void onAttackWhileDowned(AttackEntityEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		JsonObject sheet = SheetLoader.getServerSheet(event.getEntity().getStringUUID());
		if (sheet != null && isDowned(sheet)) event.setCanceled(true);
	}

	//Reanimar: interactuar con un jugador caído lo estabiliza al instante, sin tirada.
	@SubscribeEvent
	public static void onInteractWithDowned(PlayerInteractEvent.EntityInteract event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!(event.getTarget() instanceof ServerPlayer target)) return;
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null || !isDowned(sheet)) return;

		String reviverName = event.getEntity().getName().getString();
		String targetName = characterName(sheet, target);
		stabilize(target, sheet, "¡" + reviverName + " te ha reanimado!");
		ChatFeedback.broadcast(target, ChatFeedback.revived(reviverName, targetName));
	}

	/**
	 * <p>Llamado al recibir un {@code DeathSaveRollMessage} del cliente caído.</p>
	 */
	public static void handleRollRequest(ServerPlayer player) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null || !isDowned(sheet)) return;

		//Mismo gating que un ataque o un hechizo: en 5e real solo se tira UNA salvación de muerte por
		//turno. Sin esto, hacer clic repetido resolvía las 3 tiradas en fracciones de segundo, sin dar
		//tiempo a que un aliado reanime. Fuera de modo turnos (tryAct siempre deja pasar) no cambia nada.
		if (!TurnManager.tryAct(player)) { TurnManager.notifyCantAct(player); return; }

		int roll = ThreadLocalRandom.current().nextInt(1, 21);
		String characterName = characterName(sheet, player);
		CombatFx.diceTick(player);

		if (roll == 20) {
			stabilize(player, sheet, "¡20 Natural! Vuelves en ti");
			ChatFeedback.broadcast(player, ChatFeedback.naturalTwenty(characterName));
			return;
		}

		int successes = sheet.has("deathSaveSuccesses") ? sheet.get("deathSaveSuccesses").getAsInt() : 0;
		int failures = sheet.has("deathSaveFailures") ? sheet.get("deathSaveFailures").getAsInt() : 0;

		if (roll == 1) failures += 2;
		else if (roll >= 10) successes += 1;
		else failures += 1;
		successes = Math.min(successes, 3);
		failures = Math.min(failures, 3);

		sheet.addProperty("deathSaveSuccesses", successes);
		sheet.addProperty("deathSaveFailures", failures);

		ChatFeedback.broadcast(player, ChatFeedback.deathSaveRoll(characterName, roll, successes, failures));

		if (successes >= 3) {
			stabilize(player, sheet, "¡Estabilizado!");
		} else if (failures >= 3) {
			allowRealDeath.add(player.getUUID());
			sendSheetUpdate(player, sheet);
			player.hurt(player.damageSources().generic(), Float.MAX_VALUE);
			//player.hurt() ya disparó (y limpió) la marca si de verdad murió. Si algo interceptó la muerte
			//antes de LivingDeathEvent (un tótem de inmortalidad, absorción total...), la marca se habría
			//quedado pegada para siempre y la PRÓXIMA muerte real de este jugador, por cualquier causa no
			//relacionada, se habría saltado el sistema de salvaciones en silencio. No-op si ya se limpió.
			allowRealDeath.remove(player.getUUID());
		} else {
			sendSheetUpdate(player, sheet);
		}
	}

	private static void goDown(ServerPlayer player, JsonObject sheet) {
		player.setHealth(1.0f);
		sheet.addProperty("downed", true);
		sheet.addProperty("deathSaveSuccesses", 0);
		sheet.addProperty("deathSaveFailures", 0);

		player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, INFINITE_DURATION, 0, false, false));
		player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, INFINITE_DURATION, 4, false, false));
		player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, INFINITE_DURATION, 9, false, false));

		sendSheetUpdate(player, sheet);
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new DeathSaveOpenMessage());
		CombatFx.downed(player);
		ChatFeedback.broadcast(player, ChatFeedback.downed(characterName(sheet, player)));
	}

	private static void stabilize(ServerPlayer player, JsonObject sheet, String titleText) {
		sheet.addProperty("downed", false);
		sheet.addProperty("deathSaveSuccesses", 0);
		sheet.addProperty("deathSaveFailures", 0);

		player.removeEffect(MobEffects.BLINDNESS);
		player.removeEffect(MobEffects.WEAKNESS);
		player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
		if (player.getHealth() < 1.0f) player.setHealth(1.0f);

		sendSheetUpdate(player, sheet);
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new DeathSaveCloseMessage());
		CombatFx.saved(player, titleText);
	}

	//Reenvía el estado a quien acaba de unirse por si estaba caído desde antes de desconectarse.
	public static void resendStateOnJoin(ServerPlayer player, JsonObject sheet) {
		if (isDowned(sheet)) {
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new DeathSaveOpenMessage());
		}
	}

	private static boolean isDowned(JsonObject sheet) {
		return sheet.has("downed") && sheet.get("downed").getAsBoolean();
	}

	private static String characterName(JsonObject sheet, ServerPlayer player) {
		return SheetLoader.characterNameOf(sheet, player);
	}

	private static void sendSheetUpdate(ServerPlayer player, JsonObject sheet) {
		byte[] data = sheet.toString().getBytes();
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetClientMessage(data));
	}
}
