package net.hawthorn.dndsheets.client;

import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Drops the tables a server pushed (see {@code Config.syncTo}) when the player leaves it. */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, value = Dist.CLIENT)
public class ConfigSyncClient {
	@SubscribeEvent
	public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
		Config.clearRemote();
	}
}
