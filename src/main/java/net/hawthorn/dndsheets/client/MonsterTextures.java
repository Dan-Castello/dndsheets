package net.hawthorn.dndsheets.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * <p>Colours an archetype's mask texture into a variant, once, on the client. An archetype ships ONE
 * {@code textures/entity/<model>/mask.png} (per pixel: which colour role it is, how much of the dark colour to mix in, a
 * highlight amount) and one {@code variants.json} of palettes; the first time a variant is drawn this paints it into a
 * {@link DynamicTexture} and caches it. So a dragon colour is one line of JSON, not one more PNG, and a monster may
 * even ask for a raw {@code "#RRGGBB"} variant that derives its palette from that colour. An imported model instead
 * ships {@code textures/entity/<model>/<variant>.png}, which is used as it is.</p>
 *
 * <p>Mask encoding (RGBA, written by {@code tools/blockbench/mobs/mobkit.py}): R = mix toward {@code dark},
 * G = role * 40 (base, dark, belly, horn, membrane, eye), B = whiten, A = 255 painted / 0 empty.</p>
 */
public final class MonsterTextures {
	private static final String[] ROLES = {"base", "dark", "belly", "horn", "membrane", "eye"};
	private static final Map<String, ResourceLocation> CACHE = new HashMap<>();

	private MonsterTextures() {
	}

	public static ResourceLocation get(String model, String variant) {
		return CACHE.computeIfAbsent(model + "/" + variant, key -> build(model, variant));
	}

	/** Called on every resource reload: palettes or masks may have changed. */
	public static void clear() {
		//Only the coloured ones are ours to release; a plain PNG belongs to the texture manager's normal lifecycle.
		CACHE.values().stream().filter(location -> location.getPath().startsWith("dynamic/"))
			.forEach(location -> Minecraft.getInstance().getTextureManager().release(location));
		CACHE.clear();
	}

	private static ResourceLocation build(String model, String variant) {
		try {
			ResourceManager resources = Minecraft.getInstance().getResourceManager();
			//An imported model (tools/blockbench/import) ships finished art: one PNG per variant, drawn as is.
			if (variant.matches("[a-z0-9_.-]+")) {
				ResourceLocation plain = new ResourceLocation(DndsheetsMod.MODID, "textures/entity/" + model + "/" + variant + ".png");
				if (resources.getResource(plain).isPresent()) return plain;
			}
			int[][] palette = palette(resources, model, variant);
			ResourceLocation maskLocation = new ResourceLocation(DndsheetsMod.MODID, "textures/entity/" + model + "/mask.png");
			try (InputStream in = resources.open(maskLocation); NativeImage mask = NativeImage.read(in)) {
				NativeImage out = new NativeImage(mask.getWidth(), mask.getHeight(), false);
				for (int y = 0; y < mask.getHeight(); y++) {
					for (int x = 0; x < mask.getWidth(); x++) {
						out.setPixelRGBA(x, y, colour(mask.getPixelRGBA(x, y), palette));
					}
				}
				ResourceLocation id = new ResourceLocation(DndsheetsMod.MODID,
					"dynamic/" + model + "/" + variant.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_"));
				Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(out));
				return id;
			}
		} catch (Exception e) {
			DndsheetsMod.LOGGER.warn("dndsheets: cannot colour {}/{} ({}); drawing the missing texture.", model, variant, e.toString());
			return MissingTextureAtlasSprite.getLocation();
		}
	}

	/** ABGR in, ABGR out (NativeImage's byte order). */
	private static int colour(int abgr, int[][] palette) {
		int alpha = abgr >>> 24;
		if (alpha == 0) return 0;
		float dark = (abgr & 0xFF) / 255f;
		int role = Math.min(((abgr >> 8) & 0xFF) / 40, ROLES.length - 1);
		float white = ((abgr >> 16) & 0xFF) / 255f;
		int[] base = palette[role], shade = palette[1];
		int r = mix(mix(base[0], shade[0], dark), 255, white), g = mix(mix(base[1], shade[1], dark), 255, white), b = mix(mix(base[2], shade[2], dark), 255, white);
		return (255 << 24) | (b << 16) | (g << 8) | r;
	}

	private static int mix(int a, int b, float t) {
		return Math.max(0, Math.min(255, Math.round(a + (b - a) * t)));
	}

	private static int[][] palette(ResourceManager resources, String model, String variant) throws Exception {
		if (variant.startsWith("#")) return derived(Integer.parseInt(variant.substring(1), 16));
		ResourceLocation file = new ResourceLocation(DndsheetsMod.MODID, "textures/entity/" + model + "/variants.json");
		try (InputStream in = resources.open(file)) {
			JsonObject all = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject one = all.has(variant) ? all.getAsJsonObject(variant) : all.getAsJsonObject(all.keySet().iterator().next());
			int[][] palette = new int[ROLES.length][];
			for (int i = 0; i < ROLES.length; i++) {
				JsonArray rgb = one.getAsJsonArray(ROLES[i]);
				palette[i] = new int[] {rgb.get(0).getAsInt(), rgb.get(1).getAsInt(), rgb.get(2).getAsInt()};
			}
			return palette;
		}
	}

	/** A palette from a single colour: darker shade, paler belly and horn, a warm eye. */
	private static int[][] derived(int rgb) {
		int[] base = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
		return new int[][] {base, scale(base, 0.6f), toward(base, 0.55f), toward(base, 0.75f), scale(base, 0.85f), {255, 228, 90}};
	}

	private static int[] scale(int[] c, float k) {
		return new int[] {Math.round(c[0] * k), Math.round(c[1] * k), Math.round(c[2] * k)};
	}

	private static int[] toward(int[] c, float t) {
		return new int[] {mix(c[0], 255, t), mix(c[1], 255, t), mix(c[2], 255, t)};
	}
}
