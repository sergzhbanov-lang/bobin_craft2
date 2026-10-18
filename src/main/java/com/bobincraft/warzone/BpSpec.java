package com.bobincraft.warzone;

/**
 * Описание блока в чертеже БЕЗ зависимости от Minecraft: id (без "minecraft:") + отсортированные свойства.
 * Превращается в BlockState только при вставке в чанк ({@link BlueprintBlitter}). Иммутабельный, сравнивается по тексту.
 */
public final class BpSpec {
    public static final BpSpec AIR = new BpSpec("air", new String[0], new String[0]);

    public final String id;
    private final String[] keys;
    private final String[] vals;
    private final String text;

    private BpSpec(String id, String[] keys, String[] vals) {
        this.id = id;
        this.keys = keys;
        this.vals = vals;
        StringBuilder sb = new StringBuilder(id);
        if (keys.length > 0) {
            sb.append('[');
            for (int i = 0; i < keys.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(keys[i]).append('=').append(vals[i]);
            }
            sb.append(']');
        }
        this.text = sb.toString();
    }

    public static BpSpec of(String id) {
        return id.equals("air") ? AIR : new BpSpec(id, new String[0], new String[0]);
    }

    public BpSpec with(String key, String val) {
        int at = -1;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(key)) at = i;
        if (at >= 0) {
            if (vals[at].equals(val)) return this;
            String[] nv = vals.clone();
            nv[at] = val;
            return new BpSpec(id, keys, nv);
        }
        String[] nk = new String[keys.length + 1];
        String[] nv = new String[keys.length + 1];
        int p = 0;
        boolean placed = false;
        for (int i = 0; i < keys.length; i++) {
            if (!placed && key.compareTo(keys[i]) < 0) {
                nk[p] = key;
                nv[p++] = val;
                placed = true;
            }
            nk[p] = keys[i];
            nv[p++] = vals[i];
        }
        if (!placed) {
            nk[p] = key;
            nv[p] = val;
        }
        return new BpSpec(id, nk, nv);
    }

    public BpSpec with(String key, boolean val) {
        return with(key, val ? "true" : "false");
    }

    public String prop(String key) {
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(key)) return vals[i];
        return null;
    }

    public int propCount() {
        return keys.length;
    }

    public String key(int i) {
        return keys[i];
    }

    public String val(int i) {
        return vals[i];
    }

    public boolean isAir() {
        return id.equals("air") || id.equals("cave_air") || id.equals("void_air");
    }

    /** Забор (не калитка): соединяется с заборами и полными блоками. */
    public boolean isFence() {
        return id.endsWith("_fence");
    }

    /** Панель стекла / железные прутья. */
    public boolean isPane() {
        return id.endsWith("_pane") || id.equals("iron_bars");
    }

    public boolean isWall() {
        return id.endsWith("_wall");
    }

    /** Полный непрозрачный куб, к которому подключаются заборы и панели (эвристика по id). */
    public boolean isFullBlock() {
        if (isAir()) return false;
        String s = id;
        if (s.contains("slab") || s.contains("stairs") || s.contains("fence") || s.contains("wall")
                || s.contains("pane") || s.contains("glass") || s.contains("door") || s.contains("lantern")
                || s.contains("ladder") || s.contains("torch") || s.contains("carpet") || s.contains("chain")
                || s.contains("leaves") || s.contains("snow") || s.contains("campfire") || s.contains("bars")) {
            return false;
        }
        return s.endsWith("_planks") || s.endsWith("_log") || s.endsWith("_wood") || s.endsWith("stone")
                || s.endsWith("cobblestone") || s.endsWith("bricks") || s.endsWith("blackstone")
                || s.endsWith("concrete") || s.endsWith("dirt") || s.startsWith("deepslate")
                || s.endsWith("_block") || s.equals("barrel") || s.endsWith("terracotta") || s.endsWith("sandstone");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BpSpec b && b.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
