package com.bobincraft.raid;

import com.bobincraft.warzone.WarzoneBasePlan;
import com.bobincraft.warzone.WarzoneTerrain;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Small server-authored map; sampling its terrain never loads a world chunk. */
public record TacticalMapData(int min, int size, int resolution, int[] colors,
                              List<Marker> pois, List<Segment> roads,
                              List<DeploymentPlanner.Point> deployments,
                              DeploymentPlanner.Point extraction, long rotation, boolean fieldMap) {
    private static final int RESOLUTION = 96;
    private static final int GENERATION_VERSION = DeploymentPlanner.GENERATION_VERSION;
    private static final int MAX_POIS = 2048;
    private static final int MAX_ROADS = 8192;
    private static final int MAX_LABEL = 128;
    private static final int DEPLOYMENT_COUNT = 3;
    private static final int MAX_CACHE_ENTRIES = 4;

    private record Key(long seed, int size, int version) {}
    private record KnownMarker(int discoveryId, Marker marker) {}
    private record BaseMap(int[] colors, List<KnownMarker> pois, List<Segment> roads) {}

    /** Original deployment constructor keeps exactly three validated server choices. */
    public TacticalMapData(int min, int size, int resolution, int[] colors, List<Marker> pois,
                           List<Segment> roads, List<DeploymentPlanner.Point> deployments,
                           DeploymentPlanner.Point extraction, long rotation) {
        this(min, size, resolution, colors, pois, roads, deployments, extraction, rotation, false);
    }

    private static final Map<Key, BaseMap> CACHE = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, BaseMap> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    public record Marker(int x, int z, int danger, String label) {
        public Marker {
            validateDanger(danger);
            validateLabel(label);
        }
    }

    public record Segment(int x0, int z0, int x1, int z1) {}

    public TacticalMapData {
        validateBounds(min, size, resolution);
        Objects.requireNonNull(colors, "colors");
        Objects.requireNonNull(pois, "pois");
        Objects.requireNonNull(roads, "roads");
        Objects.requireNonNull(deployments, "deployments");
        if (!fieldMap) Objects.requireNonNull(extraction, "extraction");
        if (colors.length != resolution * resolution) throw invalid("pixel count");
        if (pois.size() > MAX_POIS || roads.size() > MAX_ROADS) throw invalid("map detail count");
        if (deployments.size() != (fieldMap ? 0 : DEPLOYMENT_COUNT)) throw invalid("deployment count");
        for (Marker marker : pois) {
            Objects.requireNonNull(marker, "marker");
            validatePosition(min, size, marker.x(), marker.z());
        }
        for (Segment road : roads) {
            Objects.requireNonNull(road, "road");
            validatePosition(min, size, road.x0(), road.z0());
            validatePosition(min, size, road.x1(), road.z1());
        }
        Set<Integer> ids = new HashSet<>();
        Set<Long> positions = new HashSet<>();
        for (DeploymentPlanner.Point point : deployments) {
            validatePoint(min, size, point);
            if (point.id() < 1 || point.id() > DEPLOYMENT_COUNT || !ids.add(point.id())) {
                throw invalid("deployment id");
            }
            if (!positions.add(((long) point.x() << 32) ^ (point.z() & 0xffffffffL))) {
                throw invalid("duplicate deployment position");
            }
        }
        if (extraction != null) {
            validatePoint(min, size, extraction);
            if (extraction.id() != 0) throw invalid("extraction id");
        }
        if (fieldMap && rotation != 0) throw invalid("field map deployment token");
        // The cached base map keeps its own array; each outgoing payload owns a copy.
        colors = colors.clone();
        pois = List.copyOf(pois);
        roads = List.copyOf(roads);
        deployments = List.copyOf(deployments);
    }

    public static TacticalMapData build(WarzoneTerrain terrain,
                                         List<DeploymentPlanner.Point> deployments,
                                         DeploymentPlanner.Point extraction, long rotation) {
        return build(terrain, deployments, extraction, rotation, null);
    }

    public static TacticalMapData build(WarzoneTerrain terrain,
                                         List<DeploymentPlanner.Point> deployments,
                                         DeploymentPlanner.Point extraction, long rotation,
                                         Set<Integer> discoveries) {
        BaseMap base = baseMap(terrain);
        return new TacticalMapData(terrain.min, terrain.size, RESOLUTION, base.colors(),
                knownMarkers(base, discoveries), base.roads(), deployments, extraction, rotation);
    }

    /** Read-only raid packet deliberately has no deployment points, session token, or live entities. */
    public static TacticalMapData fieldMap(WarzoneTerrain terrain, Set<Integer> discoveries,
                                           DeploymentPlanner.Point revealedExtraction) {
        BaseMap base = baseMap(terrain);
        return new TacticalMapData(terrain.min, terrain.size, RESOLUTION, base.colors(),
                knownMarkers(base, Objects.requireNonNull(discoveries)), base.roads(), List.of(),
                revealedExtraction, 0, true);
    }

    private static List<Marker> knownMarkers(BaseMap base, Set<Integer> discoveries) {
        return base.pois().stream().filter(marker -> discoveries == null || discoveries.contains(marker.discoveryId()))
                .map(KnownMarker::marker).toList();
    }

    private static BaseMap baseMap(WarzoneTerrain terrain) {
        Objects.requireNonNull(terrain, "terrain");
        validateBounds(terrain.min, terrain.size, RESOLUTION);
        Key key = new Key(terrain.seed, terrain.size, GENERATION_VERSION);
        synchronized (CACHE) {
            BaseMap base = CACHE.get(key);
            if (base == null) {
                base = sampleMap(terrain);
                CACHE.put(key, base);
            }
            return base;
        }
    }

    /** Existing positive IDs remain base discoveries; disjoint negative IDs extend the same saved set. */
    static int hideoutDiscoveryId(int id) { return -1_000_000 - id; }
    static int checkpointDiscoveryId(int id) { return -2_000_000 - id; }
    static int battlefieldDiscoveryId(int id) { return -3_000_000 - id; }

    /** Metadata-only discovery runs every two seconds; it never scans blocks or stores a player trail. */
    public static boolean discover(WarzoneTerrain terrain, double x, double z, Set<Integer> discoveries) {
        boolean changed = false;
        if (terrain.basePlan() != null) {
            for (var site : terrain.basePlan().sites()) {
                if (near(x, z, site.cx, site.cz, 96)) changed |= discoveries.add(site.id);
            }
        }
        for (var hideout : terrain.hideoutPlan().hideouts()) {
            if (near(x, z, hideout.cx(), hideout.cz(), 24)) changed |= discoveries.add(hideoutDiscoveryId(hideout.id()));
        }
        for (var checkpoint : terrain.poiPlan().checkpoints()) {
            if (near(x, z, checkpoint.cx(), checkpoint.cz(), 36)) changed |= discoveries.add(checkpointDiscoveryId(checkpoint.id()));
        }
        for (var field : terrain.poiPlan().battlefields()) {
            if (near(x, z, field.cx(), field.cz(), 48)) changed |= discoveries.add(battlefieldDiscoveryId(field.id()));
        }
        return changed;
    }

    private static boolean near(double x, double z, int targetX, int targetZ, int radius) {
        double dx = x - targetX, dz = z - targetZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    private static BaseMap sampleMap(WarzoneTerrain terrain) {
        int[] pixels = new int[RESOLUTION * RESOLUTION];
        int[] heights = new int[pixels.length];
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        for (int z = 0; z < RESOLUTION; z++) {
            int worldZ = terrain.min + (int) (((2L * z + 1) * terrain.size) / (2L * RESOLUTION));
            for (int x = 0; x < RESOLUTION; x++) {
                int worldX = terrain.min + (int) (((2L * x + 1) * terrain.size) / (2L * RESOLUTION));
                terrain.sample(worldX, worldZ, sample);
                int index = z * RESOLUTION + x;
                heights[index] = sample.height;
                pixels[index] = terrainColor(sample);
                if (sample.height > WarzoneTerrain.SEA_LEVEL && sample.height < 110
                        && sample.region != WarzoneTerrain.Region.BADLANDS && sample.basePad < 0.3
                        && sample.road < 0.3 && sample.path < 0.3) {
                    pixels[index] = shade(pixels[index], 1.0 - terrain.forest(worldX, worldZ) * 0.23);
                }
            }
        }
        // Relief uses adjacent sampled pixels, without any extra world or chunk queries.
        for (int z = 0; z < RESOLUTION; z++) {
            for (int x = 0; x < RESOLUTION; x++) {
                int index = z * RESOLUTION + x;
                int west = z * RESOLUTION + Math.max(0, x - 1);
                int north = Math.max(0, z - 1) * RESOLUTION + x;
                double slope = heights[index] - (heights[west] + heights[north]) * 0.5;
                double shade = Math.max(0.72, Math.min(1.23, 1.0 + slope * 0.014));
                pixels[index] = shade(pixels[index], shade);
            }
        }

        List<KnownMarker> markers = new ArrayList<>();
        List<Segment> roads = new ArrayList<>();
        WarzoneBasePlan plan = terrain.basePlan();
        if (plan != null) {
            for (WarzoneBasePlan.Site site : plan.sites()) {
                addMarker(markers, terrain, site.id, site.cx, site.cz, site.callsign);
            }
            for (WarzoneBasePlan.RoadSegment road : plan.roads()) {
                addRoad(roads, terrain, road.x0(), road.z0(), road.x1(), road.z1());
            }
            for (WarzoneBasePlan.TrailPath trail : plan.paths()) {
                for (int i = 1; i < trail.length(); i++) {
                    addRoad(roads, terrain, (int) Math.round(trail.px[i - 1]), (int) Math.round(trail.pz[i - 1]),
                            (int) Math.round(trail.px[i]), (int) Math.round(trail.pz[i]));
                }
            }
        }
        for (var hideout : terrain.hideoutPlan().hideouts()) {
            addMarker(markers, terrain, hideoutDiscoveryId(hideout.id()), hideout.cx(), hideout.cz(), hideout.name());
        }
        for (var checkpoint : terrain.poiPlan().checkpoints()) {
            addMarker(markers, terrain, checkpointDiscoveryId(checkpoint.id()), checkpoint.cx(), checkpoint.cz(), checkpoint.name());
        }
        for (var battlefield : terrain.poiPlan().battlefields()) {
            addMarker(markers, terrain, battlefieldDiscoveryId(battlefield.id()), battlefield.cx(), battlefield.cz(), battlefield.name());
        }
        return new BaseMap(pixels, List.copyOf(markers), List.copyOf(roads));
    }

    private static void addMarker(List<KnownMarker> markers, WarzoneTerrain terrain, int id, int x, int z, String label) {
        if (markers.size() < MAX_POIS && terrain.inside(x, z)) {
            markers.add(new KnownMarker(id, new Marker(x, z, DeploymentPlanner.danger(terrain, x, z), label)));
        }
    }

    private static void addRoad(List<Segment> roads, WarzoneTerrain terrain, int x0, int z0, int x1, int z1) {
        if (roads.size() < MAX_ROADS && terrain.inside(x0, z0) && terrain.inside(x1, z1)) {
            roads.add(new Segment(x0, z0, x1, z1));
        }
    }

    private static int terrainColor(WarzoneTerrain.Sample sample) {
        if (sample.height <= WarzoneTerrain.SEA_LEVEL) {
            return sample.height < WarzoneTerrain.SEA_LEVEL - 7 ? 0xff324b4a : 0xff4f6860;
        }
        if (sample.height >= 133) return 0xffa9ac95;
        if (sample.edge < 16) return 0xff666b6c;
        int color = switch (sample.region) {
            case PLAINS -> 0xff78805a;
            case HIGHLANDS -> sample.mountain > 0.45 ? 0xff8c8c75 : 0xff626e51;
            case BADLANDS -> sample.desert > 0.48 ? 0xffa49469 : 0xff8a7052;
            case MARSH -> sample.bog > 0.45 ? 0xff47594b : 0xff596c53;
        };
        if (sample.scar > 0.5) color = 0xff65594b;
        if (sample.basePad > 0.75) color = shade(color, 1.12);
        if (sample.road > 0.5 || sample.path > 0.6) color = 0xffaaa087;
        return color;
    }

    private static int shade(int color, double multiplier) {
        int r = Math.min(255, (int) (((color >>> 16) & 255) * multiplier));
        int g = Math.min(255, (int) (((color >>> 8) & 255) * multiplier));
        int b = Math.min(255, (int) ((color & 255) * multiplier));
        return 0xff000000 | r << 16 | g << 8 | b;
    }

    public void write(PacketByteBuf buf) {
        buf.writeInt(min);
        buf.writeVarInt(size);
        buf.writeVarInt(resolution);
        buf.writeVarInt(colors.length);
        for (int color : colors) buf.writeInt(color);
        buf.writeVarInt(pois.size());
        for (Marker marker : pois) {
            buf.writeInt(marker.x());
            buf.writeInt(marker.z());
            buf.writeVarInt(marker.danger());
            buf.writeString(marker.label(), MAX_LABEL);
        }
        buf.writeVarInt(roads.size());
        for (Segment road : roads) {
            buf.writeInt(road.x0());
            buf.writeInt(road.z0());
            buf.writeInt(road.x1());
            buf.writeInt(road.z1());
        }
        buf.writeBoolean(fieldMap);
        buf.writeVarInt(deployments.size());
        for (DeploymentPlanner.Point deployment : deployments) writePoint(buf, deployment);
        buf.writeBoolean(extraction != null);
        if (extraction != null) writePoint(buf, extraction);
        // Field packets carry no deployment-session token at all.
        if (!fieldMap) buf.writeLong(rotation);
    }

    public static TacticalMapData read(PacketByteBuf buf) {
        int min = buf.readInt();
        int size = buf.readVarInt();
        int resolution = buf.readVarInt();
        validateBounds(min, size, resolution);
        int pixelCount = readCount(buf, RESOLUTION * RESOLUTION, "pixel count");
        if (pixelCount != resolution * resolution) throw invalid("pixel count");
        if (buf.readableBytes() < pixelCount * Integer.BYTES) throw invalid("truncated pixels");
        int[] colors = new int[pixelCount];
        for (int i = 0; i < pixelCount; i++) colors[i] = buf.readInt();
        int markerCount = readCount(buf, MAX_POIS, "poi count");
        List<Marker> markers = new ArrayList<>(markerCount);
        for (int i = 0; i < markerCount; i++) {
            markers.add(new Marker(buf.readInt(), buf.readInt(), buf.readVarInt(), buf.readString(MAX_LABEL)));
        }
        int roadCount = readCount(buf, MAX_ROADS, "road count");
        if (buf.readableBytes() < roadCount * 4 * Integer.BYTES) throw invalid("truncated roads");
        List<Segment> roads = new ArrayList<>(roadCount);
        for (int i = 0; i < roadCount; i++) {
            roads.add(new Segment(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()));
        }
        boolean fieldMap = buf.readBoolean();
        int deploymentCount = readCount(buf, DEPLOYMENT_COUNT, "deployment count");
        if (deploymentCount != (fieldMap ? 0 : DEPLOYMENT_COUNT)) throw invalid("deployment count");
        List<DeploymentPlanner.Point> deployments = new ArrayList<>(deploymentCount);
        for (int i = 0; i < deploymentCount; i++) deployments.add(readPoint(buf));
        DeploymentPlanner.Point extraction = buf.readBoolean() ? readPoint(buf) : null;
        TacticalMapData map = new TacticalMapData(min, size, resolution, colors, markers, roads,
                deployments, extraction, fieldMap ? 0 : buf.readLong(), fieldMap);
        if (buf.isReadable()) throw invalid("trailing payload");
        return map;
    }

    private static void writePoint(PacketByteBuf buf, DeploymentPlanner.Point point) {
        buf.writeVarInt(point.id());
        buf.writeInt(point.x());
        buf.writeInt(point.y());
        buf.writeInt(point.z());
        buf.writeVarInt(point.danger());
        buf.writeString(point.label(), MAX_LABEL);
    }

    private static DeploymentPlanner.Point readPoint(PacketByteBuf buf) {
        return new DeploymentPlanner.Point(buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                buf.readVarInt(), buf.readString(MAX_LABEL));
    }

    private static int readCount(PacketByteBuf buf, int maximum, String field) {
        int count = buf.readVarInt();
        if (count < 0 || count > maximum) throw invalid(field);
        return count;
    }

    private static void validateBounds(int min, int size, int resolution) {
        if (size < 256 || size > 4096 || size % 16 != 0 || min != -size / 2) throw invalid("arena bounds");
        if (resolution != 64 && resolution != RESOLUTION) throw invalid("map resolution");
    }

    private static void validatePosition(int min, int size, int x, int z) {
        if (x < min || x >= min + size || z < min || z >= min + size) throw invalid("position outside arena");
    }

    private static void validatePoint(int min, int size, DeploymentPlanner.Point point) {
        Objects.requireNonNull(point, "point");
        validatePosition(min, size, point.x(), point.z());
        if (point.y() < WarzoneTerrain.MIN_Y + 1 || point.y() >= WarzoneTerrain.MIN_Y + WarzoneTerrain.HEIGHT) {
            throw invalid("deployment height");
        }
        validateDanger(point.danger());
        validateLabel(point.label());
    }

    private static void validateDanger(int danger) {
        if (danger < 0 || danger > 100) throw invalid("danger");
    }

    private static void validateLabel(String label) {
        Objects.requireNonNull(label, "label");
        if (label.isBlank() || label.length() > MAX_LABEL || label.chars().anyMatch(Character::isISOControl)) {
            throw invalid("label");
        }
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid tactical map " + field);
    }
}
