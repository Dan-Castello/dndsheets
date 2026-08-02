package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>Inspiración Bárdica: clic derecho del bardo sobre OTRO jugador (con el Cuerno de Inspiración,
 * {@code {dndsheets:{bardicInspiration:true}}}) le da un dado ({@value #DIE}, tirado ya en el momento de
 * concederlo) que se suma a su PRÓXIMA tirada de ataque, durante {@value #DURATION_ROUNDS} asaltos (10
 * minutos de 5e). Igual que Furia, la duración cuenta en asaltos si el modo turnos está activo al
 * concederla, o en ticks reales si no — ver {@link TurnManager#onRoundsPass}.</p>
 *
 * <p><b>Alcance deliberadamente reducido</b>: 5e deja usar este dado en un ataque, una prueba de
 * característica O una salvación; aquí solo se engancha a la tirada de ataque (mismo punto donde ya vive
 * la ventaja/desventaja en {@link CombatManager}/{@link SpellCastManager}) — extenderlo a pruebas y
 * salvaciones tocaría también {@code RollAnnouncerProcedure}, la pantalla de la hoja. Tampoco hay límite
 * de usos por descanso (en 5e es el modificador de Carisma del bardo); se puede volver a conceder cuando
 * se quiera, igual que Furia.</p>
 */
@Mod.EventBusSubscriber
public class BardInspirationManager {
	private static final String DIE = "1d6"; //5e escala a 1d8/1d10/1d12 en niveles 5/10/15; simplificado a un dado fijo.
	private static final int DURATION_ROUNDS = 100; //10 minutos de 5e = 100 asaltos.
	private static final int DURATION_TICKS = 20 * 60 * 10; //10 minutos reales fuera de modo turnos.

	//Token por objetivo: cada grant() se lleva el suyo, y su temporizador de expiración solo borra
	//"bardicInspiration" si sigue siendo el grant MÁS RECIENTE para ese jugador. Evita el caso de una
	//segunda concesión (antes de que expire la primera) siendo borrada de más por el temporizador viejo —
	//y a diferencia de comparar por el valor tirado, esto no falla ni siquiera si las dos tiradas
	//coinciden por azar.
	private static final Map<UUID, Integer> latestGrantToken = new ConcurrentHashMap<>();
	private static int nextToken = 0;

	@SubscribeEvent
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag tag = event.getItemStack().getTag();
		if (tag == null || !tag.contains("dndsheets") || !tag.getCompound("dndsheets").getBoolean("bardicInspiration")) return;
		if (!(event.getEntity() instanceof ServerPlayer bard) || !(event.getTarget() instanceof ServerPlayer target)) return;

		event.setCanceled(true);
		grant(bard, target);
	}

	public static void grant(ServerPlayer bard, ServerPlayer target) {
		JsonObject targetSheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (targetSheet == null) return;

		DiceManager.RollOutcome roll = DiceManager.roll(new JsonObject(), DIE);
		if (roll.result() == null) return;
		int amount = roll.result().getValue();

		targetSheet.addProperty("bardicInspiration", amount);
		CombatFx.activate(target);

		String bardName = SheetLoader.characterNameOf(SheetLoader.getServerSheet(bard.getStringUUID()), bard);
		String targetName = SheetLoader.characterNameOf(targetSheet, target);
		ChatFeedback.broadcast(bard, Component.literal(bardName + " inspira a " + targetName + ": +" + amount + " (" + roll.formatted() + ") a su próxima tirada de ataque.").withStyle(ChatFeedback.RESOURCE));

		UUID uuid = target.getUUID();
		MinecraftServer server = target.getServer();
		int myToken = ++nextToken;
		latestGrantToken.put(uuid, myToken);
		Runnable expire = () -> {
			//Si otro grant más nuevo llegó para este jugador mientras tanto, ESE es el que manda ahora —
			//este temporizador viejo no debe tocar nada.
			if (!Integer.valueOf(myToken).equals(latestGrantToken.get(uuid))) return;
			latestGrantToken.remove(uuid);
			ServerPlayer stillHere = server != null ? server.getPlayerList().getPlayer(uuid) : null;
			if (stillHere == null) return;
			JsonObject sheet = SheetLoader.getServerSheet(stillHere.getStringUUID());
			if (sheet != null) sheet.remove("bardicInspiration");
		};
		if (TurnManager.isActive()) {
			TurnManager.onRoundsPass(DURATION_ROUNDS, expire);
		} else {
			DndsheetsMod.queueServerWork(DURATION_TICKS, expire);
		}
	}

	//Público: CombatManager/SpellCastManager lo llaman justo antes de tirar un ataque, igual que
	//CombatManager.consumeAdvantage — se gasta sola en cuanto se usa, con o sin acierto.
	public static int consumeAttackBonus(JsonObject sheet) {
		if (sheet == null || !sheet.has("bardicInspiration")) return 0;
		int amount = sheet.get("bardicInspiration").getAsInt();
		sheet.remove("bardicInspiration");
		return amount;
	}

	public static ItemStack buildInspirationStack() {
		ItemStack stack = new ItemStack(Items.GOAT_HORN);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean("bardicInspiration", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.literal("Cuerno de Inspiración"));

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.literal("Clic derecho en OTRO jugador: le da un dado de inspiración.").withStyle(ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);

		return stack;
	}
}
