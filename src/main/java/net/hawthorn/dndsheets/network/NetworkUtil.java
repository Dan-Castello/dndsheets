package net.hawthorn.dndsheets.network;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

//Encapsula el esqueleto de framework repetido en cada handler de mensaje (enqueueWork+setPacketHandled,
//y DistExecutor para los que solo deben correr en el cliente) — ver AUDIT_REPORT_2026.md F13. No aplica
//a encode/decode: los campos difieren demasiado entre mensajes como para generalizarlos con genéricos
//sin perder legibilidad.
public final class NetworkUtil {
	private NetworkUtil() {}

	public static void handleOnServer(NetworkEvent.Context context, Runnable action) {
		context.enqueueWork(action);
		context.setPacketHandled(true);
	}

	public static void handleOnClient(NetworkEvent.Context context, Runnable action) {
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> action::run));
		context.setPacketHandled(true);
	}
}
