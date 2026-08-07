package net.hawthorn.dndsheets;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

//Despachador único para los ítems "botón" de un solo flag NBT que se activan con clic derecho (Kit de
//Descanso, Tótem de Furia, Contrahechizo, Escudo, Marca del Cazador, ítems de turno): antes cada manager
//se suscribía por separado a los mismos 3 eventos de interacción y releía el NBT de forma independiente
//(hasta 18 handlers por clic derecho) — ver AUDIT_TECHNICAL.md M-EVT-1. Aquí se lee una sola vez y se
//delega al manager correspondiente. Cada rama de evento solo comprueba los flags de los managers que
//originalmente escuchaban ESE evento (p.ej. Marca del Cazador solo actuaba en EntityInteract, porque
//necesita el objetivo del clic) para no cambiarle el comportamiento a nadie.
@Mod.EventBusSubscriber
public class AbilityItemDispatcher {

	@SubscribeEvent
	public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag dndTag = dndTagOf(event.getItemStack());
		if (dndTag == null) return;

		if (dndTag.getBoolean("restKit")) RestManager.tryOpenRestChoice(event);
		else if (dndTag.getBoolean("rage")) BarbarianRageManager.tryUse(event);
		else if (dndTag.getBoolean("counterspellSpell")) CounterspellManager.tryUse(event);
		else if (dndTag.getBoolean("shieldSpell")) ShieldManager.tryUse(event);
		else if (dndTag.getBoolean("turnNext")) TurnItemManager.tryUse(event, true);
		else if (dndTag.getBoolean("turnUndo")) TurnItemManager.tryUse(event, false);
	}

	@SubscribeEvent
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag dndTag = dndTagOf(event.getItemStack());
		if (dndTag == null) return;

		if (dndTag.getBoolean("restKit")) RestManager.tryOpenRestChoice(event);
		else if (dndTag.getBoolean("rage")) BarbarianRageManager.tryUse(event);
		else if (dndTag.getBoolean("turnNext")) TurnItemManager.tryUse(event, true);
		else if (dndTag.getBoolean("turnUndo")) TurnItemManager.tryUse(event, false);
	}

	@SubscribeEvent
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag dndTag = dndTagOf(event.getItemStack());
		if (dndTag == null) return;

		if (dndTag.getBoolean("restKit")) RestManager.tryOpenRestChoice(event);
		else if (dndTag.getBoolean("rage")) BarbarianRageManager.tryUse(event);
		else if (dndTag.getBoolean("hunterMark")) RangerHunterMarkManager.tryUse(event);
		else if (dndTag.getBoolean("turnNext")) TurnItemManager.tryUse(event, true);
		else if (dndTag.getBoolean("turnUndo")) TurnItemManager.tryUse(event, false);
	}

	private static CompoundTag dndTagOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets")) return null;
		return tag.getCompound("dndsheets");
	}
}
