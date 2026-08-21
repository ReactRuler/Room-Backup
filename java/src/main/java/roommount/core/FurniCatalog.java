package roommount.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FurniCatalog {
    private final Map<String, Models.FurniMeta> byClass = new HashMap<>();
    private final Map<Integer, Models.FurniMeta> floorByType = new HashMap<>();
    private final Map<Integer, Models.FurniMeta> wallByType = new HashMap<>();
    private volatile boolean ready;

    public boolean isReady() {
        return ready;
    }

    public Models.FurniMeta floorByClass(String className) {
        if (className == null) return null;
        return byClass.get(normalize(className));
    }

    public Models.FurniMeta wallByClass(String className) {
        return floorByClass(className);
    }

    public Models.FurniMeta floorByType(int typeId) {
        return floorByType.get(typeId);
    }

    public Models.FurniMeta wallByType(int typeId) {
        return wallByType.get(typeId);
    }

    public String floorClass(int typeId) {
        Models.FurniMeta m = floorByType.get(typeId);
        return m == null ? "" : m.className;
    }

    public int typeIdForClass(String className) {
        String want = normalize(className);
        if (want.isEmpty()) return -1;
        for (Map.Entry<Integer, Models.FurniMeta> e : floorByType.entrySet()) {
            if (want.equals(e.getValue().className)) return e.getKey();
        }
        return -1;
    }

    public String wallClass(int typeId) {
        Models.FurniMeta m = wallByType.get(typeId);
        return m == null ? "" : m.className;
    }

    public synchronized void loadXml(Path path) throws IOException {
        String xml = Files.readString(path, StandardCharsets.UTF_8);
        byClass.clear();
        floorByType.clear();
        wallByType.clear();
        scrape(xml, "roomitemtypes", "floor");
        scrape(xml, "wallitemtypes", "wall");
        ready = !byClass.isEmpty();
    }

    private void scrape(String xml, String section, String kind) {
        int start = xml.indexOf("<" + section + ">");
        int end = xml.indexOf("</" + section + ">");
        if (start < 0 || end < 0) return;
        String chunk = xml.substring(start, end);
        Pattern re = Pattern.compile("id=\"(\\d+)\"[^>]*classname=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</furnitype>", Pattern.CASE_INSENSITIVE);
        Matcher m = re.matcher(chunk);
        while (m.find()) {
            int typeId = Integer.parseInt(m.group(1));
            String cn = normalize(m.group(2));
            String body = m.group(3);
            int xdim = intTag(body, "xdim", kind.equals("wall") ? 0 : 1);
            int ydim = intTag(body, "ydim", kind.equals("wall") ? 0 : 1);
            int offer = intTag(body, "offerid", -1);
            boolean bc = intTag(body, "bc", 0) == 1;
            String name = strTag(body, "name", cn).replace('|', '/').replace('"', '\'');
            Models.FurniMeta meta = new Models.FurniMeta(kind, cn, xdim, ydim, offer, bc, name);
            byClass.put(cn, meta);
            if ("floor".equals(kind)) floorByType.put(typeId, meta);
            else wallByType.put(typeId, meta);
        }
        if (floorByType.isEmpty() && wallByType.isEmpty() && "floor".equals(kind)) {
            Pattern re2 = Pattern.compile("classname=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</furnitype>", Pattern.CASE_INSENSITIVE);
            Matcher m2 = re2.matcher(chunk);
            while (m2.find()) {
                String cn = normalize(m2.group(1));
                String body = m2.group(2);
                int typeId = intAttrBefore(chunk, m2.start(), "id", -1);
                int xdim = intTag(body, "xdim", 1);
                int ydim = intTag(body, "ydim", 1);
                int offer = intTag(body, "offerid", -1);
                boolean bc = intTag(body, "bc", 0) == 1;
                String name = strTag(body, "name", cn).replace('|', '/').replace('"', '\'');
                Models.FurniMeta meta = new Models.FurniMeta(kind, cn, xdim, ydim, offer, bc, name);
                byClass.put(cn, meta);
                if (typeId >= 0) floorByType.put(typeId, meta);
            }
        }
    }

    private static int intAttrBefore(String chunk, int before, String attr, int def) {
        int idx = chunk.lastIndexOf(attr + "=\"", before);
        if (idx < 0) return def;
        int s = idx + attr.length() + 2;
        int e = chunk.indexOf('"', s);
        if (e < 0) return def;
        try {
            return Integer.parseInt(chunk.substring(s, e));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static int intTag(String body, String tag, int def) {
        Matcher m = Pattern.compile("<" + tag + ">(-?\\d+)", Pattern.CASE_INSENSITIVE).matcher(body);
        if (!m.find()) return def;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static String strTag(String body, String tag, String def) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)", Pattern.CASE_INSENSITIVE).matcher(body);
        return m.find() ? m.group(1) : def;
    }

    public static String normalize(String className) {
        if (className == null) return "";
        int star = className.indexOf('*');
        String s = star >= 0 ? className.substring(0, star) : className;
        return s.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isStackMagic(String className) {
        String s = normalize(className);
        return s.contains("stackmagic") || s.contains("tile_stack");
    }

    public static boolean isStackMagicTypeId(int typeId) {
        int t = Math.abs(typeId);
        return t == 4803 || t == 4880 || t == 4881
                || t == 5103 || t == 5180 || t == 5181
                || t == 11803 || t == 11804 || t == 11805;
    }

    public static final String[] STACK_MAGIC_CLASSES = new String[]{
            "tile_stackmagic", "tile_stackmagic1", "tile_stackmagic2",
            "tile_stackmagic4x4", "tile_stackmagic6x6", "tile_stackmagic8x8"
    };

    public int typeIdForStackClass(String className) {
        return typeIdForClass(className);
    }

    public static boolean isWired(String className) {
        return normalize(className).startsWith("wf_");
    }

    public static int[] stackTileSize(String className) {
        String s = normalize(className);
        if (s.contains("8x8")) return new int[]{8, 8};
        if (s.contains("6x6")) return new int[]{6, 6};
        if (s.contains("4x4")) return new int[]{4, 4};
        if (s.contains("2x2") || s.equals("tile_stackmagic2")) return new int[]{2, 2};
        if (s.contains("1x2") || s.contains("2x1") || s.equals("tile_stackmagic1")) return new int[]{2, 1};
        if (s.equals("tile_stackmagic") || s.contains("1x1")) return new int[]{1, 1};
        return new int[]{0, 0};
    }

    public List<String> summary() {
        List<String> out = new ArrayList<>();
        out.add("floorTypes=" + floorByType.size());
        out.add("wallTypes=" + wallByType.size());
        out.add("classes=" + byClass.size());
        return out;
    }

    public List<Models.FurniMeta> all() {
        List<Models.FurniMeta> list = new ArrayList<>(byClass.values());
        list.sort(Comparator.comparing(m -> m.className));
        return list;
    }
}
