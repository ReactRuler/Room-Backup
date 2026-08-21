package roommount.core;

import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HWallItem;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SnapshotIO {
    private final Path snapDir;
    private final FurniCatalog catalog;

    public SnapshotIO(Path snapDir, FurniCatalog catalog) {
        this.snapDir = snapDir;
        this.catalog = catalog;
    }

    public Path snapDir() {
        return snapDir;
    }

    public Path mountPath(String name) {
        return snapDir.resolve(sanitize(name) + ".mount.json");
    }

    public Path gpresetPath(String name) {
        return snapDir.resolve(sanitize(name) + ".gpreset.json");
    }

    public static String sanitize(String name) {
        return name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public List<String> listNames() throws IOException {
        if (!Files.isDirectory(snapDir)) return List.of();
        try (Stream<Path> stream = Files.list(snapDir)) {
            return stream
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(n -> n.endsWith(".mount.json"))
                    .map(n -> n.substring(0, n.length() - ".mount.json".length()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }
    }

    public boolean delete(String name) throws IOException {
        if (name == null || name.trim().isEmpty()) return false;
        String id = sanitize(name);
        Path mount = mountPath(id);
        Path gpreset = gpresetPath(id);
        boolean had = Files.isRegularFile(mount) || Files.isRegularFile(gpreset);
        Files.deleteIfExists(mount);
        Files.deleteIfExists(gpreset);
        return had;
    }

    public List<Models.Target> capture(RoomTracker room) {
        return capture(room, false);
    }

    public List<Models.Target> capture(RoomTracker room, boolean includeStackMagic) {
        List<Models.Target> list = new ArrayList<>();
        for (HFloorItem f : room.floorItems()) {
            String cn = classNameOf(f);
            if (!includeStackMagic && (FurniCatalog.isStackMagic(cn)
                    || FurniCatalog.isStackMagicTypeId(f.getTypeId()))) continue;
            Models.FurniMeta meta = catalog.floorByType(f.getTypeId());
            Models.Target t = new Models.Target();
            t.type = "floor";
            t.id = f.getId();
            t.kind = f.getTypeId();
            t.className = cn;
            t.name = meta == null ? cn : meta.name;
            HPoint tile = f.getTile();
            t.x = tile.getX();
            t.y = tile.getY();
            t.z = tile.getZ();
            t.rotation = f.getFacing() == null ? 0 : f.getFacing().ordinal();
            t.state = stateOf(f);
            t.wallPos = "";
            t.offerId = meta == null ? -1 : meta.offerId;
            t.bc = meta != null && meta.bc;
            list.add(t);
        }
        for (HWallItem w : room.wallItems()) {
            String cn = catalog.wallClass(w.getTypeId());
            if (cn.isEmpty()) cn = "typeid_" + w.getTypeId();
            Models.FurniMeta meta = catalog.wallByType(w.getTypeId());
            Models.Target t = new Models.Target();
            t.type = "wall";
            t.id = w.getId();
            t.kind = w.getTypeId();
            t.className = cn;
            t.name = meta == null ? cn : meta.name;
            t.wallPos = WallFurniInfo.normalize(w.getLocation() == null ? "" : w.getLocation());
            t.state = w.getState() == null ? "" : w.getState();
            t.offerId = meta == null ? -1 : meta.offerId;
            t.bc = meta != null && meta.bc;
            list.add(t);
        }
        list.sort(Comparator
                .comparing((Models.Target t) -> t.type)
                .thenComparingInt(t -> t.x)
                .thenComparingInt(t -> t.y)
                .thenComparingDouble(t -> t.z)
                .thenComparingInt(t -> t.id));
        return list;
    }

    public void save(String name, List<Models.Target> targets) throws IOException {
        Files.createDirectories(snapDir);
        int floors = 0;
        int walls = 0;
        for (Models.Target t : targets) {
            if ("wall".equals(t.type)) walls++;
            else floors++;
        }
        JSONObject root = new JSONObject();
        root.put("version", 2);
        root.put("name", name);
        root.put("capturedAt", Instant.now().toString());
        root.put("includeWired", true);
        root.put("floorCount", floors);
        root.put("wallCount", walls);
        JSONArray furni = new JSONArray();
        for (Models.Target t : targets) {
            JSONObject o = new JSONObject();
            o.put("type", t.type);
            o.put("id", t.id);
            o.put("kind", t.kind);
            o.put("className", t.className);
            o.put("name", t.name);
            if ("wall".equals(t.type)) {
                o.put("wallPos", t.wallPos == null ? "" : t.wallPos);
            } else {
                o.put("x", t.x);
                o.put("y", t.y);
                o.put("z", t.z);
                o.put("rotation", t.rotation);
            }
            o.put("state", t.state == null ? "" : t.state);
            o.put("offerId", t.offerId);
            o.put("bc", t.bc);
            furni.put(o);
        }
        root.put("furni", furni);
        Files.writeString(mountPath(name), root.toString(2), StandardCharsets.UTF_8);

        JSONObject gp = new JSONObject();
        JSONObject wired = new JSONObject();
        wired.put("effects", new JSONArray());
        wired.put("variables", new JSONArray());
        wired.put("addons", new JSONArray());
        wired.put("conditions", new JSONArray());
        wired.put("triggers", new JSONArray());
        wired.put("selectors", new JSONArray());
        wired.put("variables_map", new JSONObject());
        gp.put("wired", wired);
        gp.put("bindings", new JSONArray());
        JSONArray gpFurni = new JSONArray();
        for (Models.Target t : targets) {
            if (!"floor".equals(t.type)) continue;
            JSONObject o = new JSONObject();
            o.put("rotation", t.rotation);
            o.put("className", t.className);
            o.put("id", t.id);
            JSONObject loc = new JSONObject();
            loc.put("x", t.x);
            loc.put("y", t.y);
            loc.put("z", t.z);
            o.put("location", loc);
            if (t.state != null && !t.state.isEmpty()) o.put("state", t.state);
            gpFurni.put(o);
        }
        gp.put("furni", gpFurni);
        JSONArray gpWall = new JSONArray();
        for (Models.Target t : targets) {
            if (!"wall".equals(t.type)) continue;
            JSONObject o = new JSONObject();
            o.put("className", t.className);
            o.put("id", t.id);
            o.put("wallPos", t.wallPos == null ? "" : t.wallPos);
            if (t.state != null && !t.state.isEmpty()) o.put("state", t.state);
            gpWall.put(o);
        }
        gp.put("wall", gpWall);
        Files.writeString(gpresetPath(name), gp.toString(2), StandardCharsets.UTF_8);
    }

    public List<Models.Target> load(String name) throws IOException {
        Path path = mountPath(name);
        if (!Files.exists(path)) throw new IOException("missing " + path);
        JSONObject root = new JSONObject(Files.readString(path, StandardCharsets.UTF_8));
        JSONArray arr = root.optJSONArray("furni");
        List<Models.Target> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Models.Target t = new Models.Target();
            t.type = o.optString("type", "");
            if (t.type.isEmpty()) {
                t.type = o.has("wallPos") && !o.optString("wallPos").isEmpty() ? "wall" : "floor";
            }
            t.id = o.optInt("id", 0);
            t.kind = o.optInt("kind", 0);
            t.className = o.optString("className", "");
            t.name = o.optString("name", t.className);
            t.x = o.optInt("x", 0);
            t.y = o.optInt("y", 0);
            t.z = o.optDouble("z", 0);
            t.rotation = o.optInt("rotation", 0);
            t.state = o.optString("state", "");
            t.wallPos = WallFurniInfo.normalize(o.optString("wallPos", ""));
            t.offerId = o.optInt("offerId", -1);
            t.bc = o.optBoolean("bc", false);
            if ("floor".equals(t.type) && (FurniCatalog.isStackMagic(t.className)
                    || FurniCatalog.isStackMagicTypeId(t.kind))) {
                continue;
            }
            list.add(t);
        }
        return list;
    }

    public String classNameOf(HFloorItem f) {
        if (f.getTypeId() < 0) {
            String sc = f.getStaticClass();
            if (sc != null && !sc.isEmpty()) return FurniCatalog.normalize(sc);
        }
        String cn = catalog.floorClass(f.getTypeId());
        if (cn.isEmpty()) cn = "typeid_" + f.getTypeId();
        return cn;
    }

    private static String stateOf(HFloorItem f) {
        try {
            if (f.getStuff() == null) return "";
            String legacy = f.getStuff().getLegacyString();
            return legacy == null ? "" : legacy;
        } catch (Exception ex) {
            return "";
        }
    }
}
