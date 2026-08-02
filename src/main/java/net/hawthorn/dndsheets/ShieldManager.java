package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.network.SheetClientMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * <p>Escudo: clic derecho marca el hechizo como "listo" en la hoja (mismo patrón que Castigo Divino/
 * Hechizo Gemelo), pero a diferencia de esos NO se consume solo por dispararse una vez: en 5e de verdad se
 * decide lanzarlo ya sabiendo si el ataque entrante acertaría, así que aquí se comprueba justo donde ya se
 * compara la tirada de ataque contra la CA ({@link CombatManager#onLivingHurt},
 * {@link MonsterActionManager#resolveAttack}) y solo gasta espacio de conjuro + reacción cuando el +5 de
 * CA de verdad convierte un acierto en un fallo. Si el golpe iba a fallar igual, o acertaría de todas
 * formas incluso con Escudo, no se gasta nada y el flag sigue listo para el siguiente ataque de la ronda.</p>
 */
@Mod.EventBusSubscriber
public class ShieldManager {
	private static final int AC_BONUS = 5;

	@SubscribeEvent
	public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag tag = event.getItemStack().getTag();
		if (tag == null || !tag.contains("dndsheets") || !tag.getCompound("dndsheets").getBoolean("shieldSpell")) return;

		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("shieldReady", true);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.literal("Escudo listo: se activará solo si un ataque te acertaría sin él.").withStyle(ChatFeedback.RESOURCE));
	}

	//Público: comprobado justo donde ya se compara la tirada de ataque contra la CA, tanto en PvP como en
	//un monstruo atacando a un jugador. Devuelve la CA a usar en ESA comparación: +5 si Escudo protegió de
	//verdad (y ya gastó el espacio + la reacción), la CA normal si no aplicaba o no hacía falta.
	public static int effectiveAc(ServerPlayer victim, int attackRollValue, int normalAc) {
		if (attackRollValue < normalAc || attackRollValue >= normalAc + AC_BONUS) return normalAc; //No cambiaría el resultado.

		JsonObject sheet = SheetLoader.getServerSheet(victim.getStringUUID());
		if (sheet == null || !sheet.has("shieldReady") || !sheet.get("shieldReady").getAsBoolean()) return normalAc;

		int slots = sheet.has("spellSlotsCurrent") ? sheet.get("spellSlotsCurrent").getAsInt() : 0;
		if (slots <= 0 || !TurnManager.tryReact(victim)) return normalAc;

		sheet.addProperty("spellSlotsCurrent", slots - 1);
		sendSheetUpdate(victim, sheet);
		victim.sendSystemMessage(Component.literal("¡Escudo! Tu CA sube a " + (normalAc + AC_BONUS) + " y el golpe falla.").withStyle(ChatFormatting.AQUA));
		return normalAc + AC_BONUS;
	}

	private static void sendSheetUpdate(ServerPlayer player, JsonObject sheet) {
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetClientMessage(sheet.toString().getBytes()));
	}

	public static ItemStack buildShieldStack() {
		ItemStack stack = new ItemStack(Items.SHIELD);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean("shieldSpell", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.literal("Escudo"));

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.literal("Clic derecho: listo para activarse solo cuando te salve de un golpe.").withStyle(ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);

		return stack;
	}
}
