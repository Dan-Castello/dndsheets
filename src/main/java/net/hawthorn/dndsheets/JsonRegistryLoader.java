package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

//Patrón repetido en TraitRegistry/PresetRegistry/SpellRegistry/MonsterRegistry: leer un array JSON de un
//archivo y, por cada elemento, validar que tenga "id", parsearlo y registrarlo — saltando (con aviso en
//LOGGER, sin abortar el archivo entero) cualquier elemento sin "id" o que falle al parsear/registrar.
//Vive en el paquete raíz (no en command/) para que cada registro pueda cargarse a sí mismo desde JSON sin
//que DndPaths (arranque del servidor) tenga que depender de la capa de comandos — ver AUDIT_TECHNICAL.md
//A-DUP-3 / M-ARQ-1. WeaponCommand no lo usa: valida varios campos obligatorios a la vez y llama a
//Config.registerWeapon con parámetros posicionales en vez de un par parse()/register() sobre un registro propio.
public class JsonRegistryLoader<T> {
	private final String kindName; //Para los mensajes de log, p.ej. "rasgo", "preset", "hechizo", "monstruo".
	private final Function<JsonObject, T> parse;
	private final Consumer<T> register;

	public JsonRegistryLoader(String kindName, Function<JsonObject, T> parse, Consumer<T> register) {
		this.kindName = kindName;
		this.parse = parse;
		this.register = register;
	}

	public int loadFile(Path file) throws IOException {
		String json = Files.readString(file);
		JsonArray items = JsonParser.parseString(json).getAsJsonArray();
		int count = 0;
		//Por elemento, no por archivo entero: un elemento malformado a mitad de la lista no debe descartar
		//en silencio a todos los que venían después.
		int index = 0;
		for (JsonElement element : items) {
			index++;
			try {
				if (!element.getAsJsonObject().has("id")) {
					DndsheetsMod.LOGGER.warn("Saltando {} #{} en {}: falta el campo \"id\".", kindName, index, file.getFileName());
					continue;
				}
				register.accept(parse.apply(element.getAsJsonObject()));
				count++;
			} catch (RuntimeException e) {
				DndsheetsMod.LOGGER.warn("Saltando {} #{} en {}: {}", kindName, index, file.getFileName(), e.toString());
			}
		}
		return count;
	}
}
