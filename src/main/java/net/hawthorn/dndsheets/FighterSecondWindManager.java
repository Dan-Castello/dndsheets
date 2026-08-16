package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>Segundo Aliento del guerrero: cura {@code 1d10 + nivel} una vez por descanso (corto o largo — ver
 * {@link RestManager#applyRest}, que llama a {@link #resetOnRest} en los dos casos, igual que en 5e de
 * verdad). Sin duración que contar en asaltos ni en ticks — a diferencia de la Furia, esto es un simple
 * "usado/no usado" que un descanso resetea, así que no necesita nada de {@link TurnManager}.</p>
 */
public class FighterSecondWindManager {
	//El "ya usado" vive en la HOJA, no en un conjunto por jugador: es del personaje (con dos personajes,
	//gastarlo con uno se lo gastaba al otro) y sobrevive a un reinicio del servidor, que antes se lo
	//devolvía a todo el mundo sin haber descansado. Ver RestResource.

	public static void use(ServerPlayer player) {
		if (!RestResource.spend(player, RestResource.SECOND_WIND)) {
			player.sendSystemMessage(Component.literal("Ya usaste Segundo Aliento. Recupéralo descansando.").withStyle(ChatFormatting.GRAY));
			return;
		}

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		int level = SheetLoader.characterLevelOf(sheet, player);
		DiceManager.RollOutcome heal = DiceManager.roll(sheet != null ? sheet : new JsonObject(), "1d10 + " + level);
		int amount = heal.result() != null ? heal.result().getValue() : level;

		player.heal(amount);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.literal("¡Segundo Aliento! Recuperas " + amount + " PG.").withStyle(ChatFeedback.RESOURCE));
	}

	//Público: RestManager lo llama para los dos tipos de descanso, corto y largo — 5e recupera este
	//recurso con cualquiera de los dos, a diferencia de los espacios de conjuro (solo descanso largo).
	public static void resetOnRest(ServerPlayer player) {
		RestResource.restore(player, RestResource.SECOND_WIND);
	}

	//--- Ítem de Segundo Aliento: se activa desde AbilityItemDispatcher en vez de suscribirse a los 3
	//eventos de interacción por separado — ver AUDIT_TECHNICAL.md M-EVT-1. Mismo patrón que el Tótem de
	//Furia (BarbarianRageManager). ---

	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (event.getEntity() instanceof ServerPlayer player) use(player);
	}

	public static ItemStack buildSecondWindStack() {
		return AbilityItem.build(Items.GOLDEN_APPLE, "secondWind", Component.literal("Segundo Aliento"),
			Component.literal("Clic derecho: cura 1d10 + nivel. Una vez por descanso.").withStyle(ChatFormatting.GRAY));
	}
}
