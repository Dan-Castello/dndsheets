package net.hawthorn.dndsheets.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.hawthorn.dndsheets.SheetLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> cliente: parche de unos pocos campos de la hoja (p.ej. "nextAttackAdvantage" tras consumir
//ventaja, "spellSlotsCurrent" tras gastar un espacio), en vez de la hoja JSON completa. Un valor JsonNull en el parche significa "borrar esta clave" en la hoja
//cacheada del cliente (ver SheetLoader.applyClientDelta), igual que el servidor la borra con
//JsonObject.remove(...). Reservado para cambios acotados a un par de campos conocidos; los cambios
//masivos (aplicar preset, cargar hoja al conectarse) siguen usando SheetClientMessage con la hoja entera.
public class SheetFieldUpdateMessage {
	byte[] data;

	public SheetFieldUpdateMessage(byte[] data) {
		this.data = data;
	}

	public SheetFieldUpdateMessage(FriendlyByteBuf buffer) {
		this.data = buffer.readByteArray();
	}

	public static void buffer(SheetFieldUpdateMessage message, FriendlyByteBuf buffer) {
		buffer.writeByteArray(message.data);
	}

	public static void handler(SheetFieldUpdateMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> handle(message.data));
	}

	public static void handle(byte[] data) {
		JsonObject patch = JsonParser.parseString(new String(data)).getAsJsonObject();
		SheetLoader.applyClientDelta(patch);
	}
}
