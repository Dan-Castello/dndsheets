package net.hawthorn.dndsheets.client;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.TurnStateMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>PG en el nombre flotante de cualquier combatiente del encuentro activo: "Goblin 4/7". El dato ya
 * viaja al cliente para el tablero del HUD (ver {@link TurnHudState}); esto solo lo repite donde el
 * jugador ya está mirando — encima del bicho al que apunta. Sin combate activo, o para una entidad
 * fuera del orden de turnos, el nombre queda exactamente como estaba (invariante 9).</p>
 *
 * <p>No dibuja nada propio: se cuelga del nombre que Minecraft ya renderiza (jugadores siempre;
 * criaturas con nombre, al apuntarlas), así que hereda gratis el billboard, la oclusión y el
 * "se ve solo cuando toca" de vanilla. Una barra flotante dibujada a mano fue descartada a propósito:
 * es render 3D nuevo para repetir un dato que ya está en el tablero del HUD.</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, value = Dist.CLIENT)
public class NameTagHp {

	@SubscribeEvent
	public static void onRenderNameTag(RenderNameTagEvent event) {
		if (!TurnHudState.active()) return;
		TurnStateMessage.RosterRow row = TurnHudState.myRow(event.getEntity().getId());
		if (row == null || row.maxHp() <= 0 || row.defeated()) return;

		//ALLOW y no solo setContent: un mob sin nombre custom nunca enseña nametag por sí solo
		//(shouldShowName es falso), así que sin forzarlo el PG solo aparecía sobre jugadores y NPC
		//nombrados — y los que importan son justo los enemigos. Solo mientras hay combate y solo para
		//quien está en el orden: fuera de eso el evento ni llega hasta aquí (invariante 9).
		event.setResult(net.minecraftforge.eventbus.api.Event.Result.ALLOW);
		ChatFormatting color = row.currentHp() * 2 >= row.maxHp() ? ChatFormatting.GREEN
			: row.currentHp() * 4 >= row.maxHp() ? ChatFormatting.YELLOW : ChatFormatting.RED;
		event.setContent(event.getContent().copy()
			.append(Component.literal(" " + row.currentHp() + "/" + row.maxHp()).withStyle(color)));
	}
}
