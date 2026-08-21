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

	/**
	 * <p>Como {@link #handleOnServer}, pero solo para quien es DM: resuelve el emisor, comprueba el permiso
	 * de operador y le pasa el jugador ya validado. Si no lo es, el paquete se descarta sin hacer nada.</p>
	 *
	 * <p>Existe porque el guard estaba copiado <b>22 veces</b>, palabra por palabra, en los mensajes que
	 * solo debería poder mandar un DM. Y eso no es fealdad: es una comprobación de permisos que hay que
	 * acordarse de escribir. Un mensaje nuevo que se olvide de ella no falla ni avisa — deja que cualquier
	 * jugador borre piezas de mazmorra, invoque monstruos o edite el contenido, porque el cliente puede
	 * mandar el paquete igual sin tener el menú abierto. Aquí no se puede olvidar: o pides el jugador por
	 * esta puerta, o no lo tienes.</p>
	 */
	public static void handleOnServerAsDm(NetworkEvent.Context context, java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> action) {
		handleOnServer(context, () -> {
			net.minecraft.server.level.ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			action.accept(dm);
		});
	}

	public static void handleOnClient(NetworkEvent.Context context, Runnable action) {
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> action::run));
		context.setPacketHandled(true);
	}
}
