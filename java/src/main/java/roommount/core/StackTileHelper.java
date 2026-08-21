package roommount.core;

import gearth.extensions.parsers.HPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class StackTileHelper {
    private StackTileHelper() {}

    public static final class Info {
        public final int furniId;
        public final HPoint home;
        public final int homeRot;
        public final int dimension;
        public HPoint drop;
        public int dropRot;

        public Info(int furniId, HPoint home, int homeRot, int dimension) {
            this.furniId = furniId;
            this.home = home;
            this.homeRot = homeRot;
            this.dimension = dimension;
            this.drop = home;
            this.dropRot = homeRot;
        }

        public int width() {
            if (dimension == -1) return 2;
            return Math.max(1, dimension);
        }

        public int height() {
            if (dimension == -1) return 1;
            return Math.max(1, dimension);
        }
    }

    public static boolean coversFootprint(int needW, int needH, int tw, int th) {
        if (tw <= 0 || th <= 0) return false;
        return Math.max(tw, th) >= Math.max(needW, needH)
                && Math.min(tw, th) >= Math.min(needW, needH);
    }

    public static int area(int w, int h) {
        return Math.max(0, w) * Math.max(0, h);
    }

    public static boolean isFloor(RoomTracker room, int x, int y) {
        if (x < 0 || y < 0) return false;
        return room.isWalkableOrOccupied(x, y);
    }

    public static boolean boundsOnlyFit(RoomTracker room, int ox, int oy, int w, int h) {
        if (w < 1 || h < 1 || ox < 0 || oy < 0) return false;
        if (!room.hasFloorplan()) return true;
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++) {
                if (!room.inPlanBounds(ox + dx, oy + dy)) return false;
            }
        }
        return true;
    }

    public static boolean boundsOnlyFit(RoomTracker room, int ox, int oy, int dim) {
        return boundsOnlyFit(room, ox, oy, dim, dim);
    }

    public static boolean squareFitsOnFloor(RoomTracker room, int ox, int oy, int dim) {
        return rectFitsOnFloor(room, ox, oy, dim, dim, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public static boolean rectFitsOnFloor(RoomTracker room, int ox, int oy, int w, int h, int allowX, int allowY) {
        if (w < 1 || h < 1 || ox < 0 || oy < 0) return false;
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++) {
                int x = ox + dx;
                int y = oy + dy;
                if (x == allowX && y == allowY) continue;
                if (!room.isWalkableOrOccupied(x, y)) return false;
            }
        }
        return true;
    }

    public static boolean stackCoversFurni(int ox, int oy, int sw, int sh, int fx, int fy, int fw, int fh) {
        return ox <= fx && oy <= fy
                && ox + sw >= fx + fw
                && oy + sh >= fy + fh;
    }

    public static int[] roomMax(RoomTracker room, int fx, int fy, int fw, int fh) {
        int maxX = room.hasFloorplan() ? Math.max(0, room.planWidth() - 1) : Math.max(fx + fw + 8, 40);
        int maxY = room.hasFloorplan() ? Math.max(0, room.planHeight() - 1) : Math.max(fy + fh + 8, 40);
        return new int[]{maxX, maxY};
    }

    public static HPoint findOriginForFurni(int fx, int fy, int fw, int fh, int sw, int sh, RoomTracker room) {
        fw = Math.max(1, fw);
        fh = Math.max(1, fh);
        sw = Math.max(1, sw);
        sh = Math.max(1, sh);
        if (sw < fw || sh < fh) return null;

        int[] max = roomMax(room, fx, fy, fw, fh);
        int maxX = max[0];
        int maxY = max[1];

        int oxMin = fx + fw - sw;
        int oyMin = fy + fh - sh;
        int oxMax = fx;
        int oyMax = fy;

        if (oxMin < 0) oxMin = 0;
        if (oyMin < 0) oyMin = 0;
        if (oxMax > maxX - sw + 1) oxMax = maxX - sw + 1;
        if (oyMax > maxY - sh + 1) oyMax = maxY - sh + 1;
        if (oxMax < 0 || oyMax < 0) return null;
        if (oxMin > oxMax || oyMin > oyMax) return null;

        List<HPoint> possible = new ArrayList<>();
        for (int ox = oxMin; ox <= oxMax; ox++) {
            for (int oy = oyMin; oy <= oyMax; oy++) {
                if (!stackCoversFurni(ox, oy, sw, sh, fx, fy, fw, fh)) continue;
                if (!boundsOnlyFit(room, ox, oy, sw, sh)) continue;
                possible.add(new HPoint(ox, oy));
            }
        }
        if (possible.isEmpty()) return null;

        possible.sort((a, b) -> {
            int da = Math.abs(a.getX() - fx) + Math.abs(a.getY() - fy);
            int db = Math.abs(b.getX() - fx) + Math.abs(b.getY() - fy);
            return da - db;
        });
        return possible.get(0);
    }

    public static HPoint forceOriginForFurni(int fx, int fy, int fw, int fh, int sw, int sh, RoomTracker room) {
        fw = Math.max(1, fw);
        fh = Math.max(1, fh);
        sw = Math.max(1, sw);
        sh = Math.max(1, sh);

        int[] max = roomMax(room, fx, fy, fw, fh);
        int maxX = max[0];
        int maxY = max[1];
        if (sw > maxX + 1) sw = maxX + 1;
        if (sh > maxY + 1) sh = maxY + 1;

        int ox = fx + fw - sw;
        int oy = fy + fh - sh;
        if (ox > fx) ox = fx;
        if (oy > fy) oy = fy;
        if (ox < 0) ox = 0;
        if (oy < 0) oy = 0;
        if (ox + sw - 1 > maxX) ox = Math.max(0, maxX - sw + 1);
        if (oy + sh - 1 > maxY) oy = Math.max(0, maxY - sh + 1);
        if (ox > fx) ox = Math.max(0, fx);
        if (oy > fy) oy = Math.max(0, fy);

        return new HPoint(ox, oy);
    }

    public static Info findBestDropLocation(int fx, int fy, int needW, int needH, List<Info> tiles, RoomTracker room) {
        if (tiles == null || tiles.isEmpty()) return null;
        int nw = Math.max(1, needW);
        int nh = Math.max(1, needH);

        List<Info> ranked = new ArrayList<>(tiles);
        ranked.sort(Comparator
                .comparingInt((Info a) -> coversFootprint(nw, nh, a.width(), a.height()) ? 0 : 1)
                .thenComparingInt((Info a) -> {
                    HPoint o = originForInfo(a, fx, fy, nw, nh, room);
                    return o != null ? 0 : 1;
                })
                .thenComparingInt((Info a) -> {
                    boolean exact = a.width() == nw && a.height() == nh
                            || a.width() == nh && a.height() == nw;
                    return exact ? 0 : 1;
                })
                .thenComparingInt((Info a) -> -area(a.width(), a.height())));

        for (Info info : ranked) {
            if (!coversFootprint(nw, nh, info.width(), info.height())) continue;
            Info placed = tryPlaceForFurni(info, fx, fy, nw, nh, room);
            if (placed != null) return placed;
        }
        for (Info info : ranked) {
            Info placed = tryPlaceForFurni(info, fx, fy, nw, nh, room);
            if (placed != null) return placed;
        }
        return null;
    }

    private static HPoint originForInfo(Info info, int fx, int fy, int nw, int nh, RoomTracker room) {
        if (info.dimension == -1) {
            HPoint a = findOriginForFurni(fx, fy, nw, nh, 2, 1, room);
            if (a != null) return a;
            return findOriginForFurni(fx, fy, nw, nh, 1, 2, room);
        }
        int dim = Math.max(1, info.dimension);
        return findOriginForFurni(fx, fy, nw, nh, dim, dim, room);
    }

    private static Info tryPlaceForFurni(Info info, int fx, int fy, int nw, int nh, RoomTracker room) {
        if (info.dimension == -1) {
            HPoint drop = findOriginForFurni(fx, fy, nw, nh, 2, 1, room);
            int rot = 0;
            if (drop == null) {
                drop = findOriginForFurni(fx, fy, nw, nh, 1, 2, room);
                rot = 2;
            }
            if (drop == null) return null;
            Info out = new Info(info.furniId, info.home, info.homeRot, -1);
            out.drop = drop;
            out.dropRot = rot;
            return out;
        }
        int dim = Math.max(1, info.dimension);
        HPoint drop = findOriginForFurni(fx, fy, nw, nh, dim, dim, room);
        if (drop == null) return null;
        Info out = new Info(info.furniId, info.home, info.homeRot, info.dimension);
        out.drop = drop;
        out.dropRot = 0;
        return out;
    }

    public static Info forceCoverDrop(Info info, int fx, int fy, int needW, int needH, RoomTracker room) {
        int nw = Math.max(1, needW);
        int nh = Math.max(1, needH);
        int sw;
        int sh;
        int rot = 0;
        if (info.dimension == -1) {
            if (nw <= 2 && nh <= 1) {
                sw = 2;
                sh = 1;
            } else {
                sw = 1;
                sh = 2;
                rot = 2;
            }
        } else {
            sw = Math.max(1, info.dimension);
            sh = sw;
        }
        HPoint drop = forceOriginForFurni(fx, fy, nw, nh, sw, sh, room);
        Info out = new Info(info.furniId, info.home, info.homeRot, info.dimension);
        out.drop = drop;
        out.dropRot = rot;
        return out;
    }

    public static Info forceCoverDrop(Info info, int tx, int ty, RoomTracker room) {
        return forceCoverDrop(info, tx, ty, 1, 1, room);
    }

    public static List<int[]> validOriginsCoveringFurni(int fx, int fy, int fw, int fh, int sw, int sh, int preferRot, RoomTracker room) {
        List<int[]> list = new ArrayList<>();
        fw = Math.max(1, fw);
        fh = Math.max(1, fh);
        sw = Math.max(1, sw);
        sh = Math.max(1, sh);
        if (sw < fw || sh < fh) return list;

        int[] max = roomMax(room, fx, fy, fw, fh);
        int maxX = max[0];
        int maxY = max[1];
        int oxMin = Math.max(0, fx + fw - sw);
        int oyMin = Math.max(0, fy + fh - sh);
        int oxMax = Math.min(fx, maxX - sw + 1);
        int oyMax = Math.min(fy, maxY - sh + 1);
        if (oxMax < oxMin || oyMax < oyMin) return list;

        for (int ox = oxMin; ox <= oxMax; ox++) {
            for (int oy = oyMin; oy <= oyMax; oy++) {
                if (!stackCoversFurni(ox, oy, sw, sh, fx, fy, fw, fh)) continue;
                if (!boundsOnlyFit(room, ox, oy, sw, sh)) continue;
                list.add(new int[]{ox, oy, preferRot});
            }
        }
        list.sort(Comparator
                .comparingInt((int[] o) -> Math.abs(o[0] - fx) + Math.abs(o[1] - fy))
                .thenComparingInt(o -> o[2] == preferRot ? 0 : 1));
        return list;
    }

    public static List<int[]> validOriginsCovering(int tx, int ty, int dim, int preferRot, RoomTracker room) {
        return validOriginsCoveringFurni(tx, ty, 1, 1, dim, dim, preferRot, room);
    }

    public static HPoint findParkSpot(RoomTracker room, int dim, List<HPoint> taken) {
        int size = Math.max(1, dim == -1 ? 2 : dim);
        int w = room.hasFloorplan() ? Math.max(1, room.planWidth()) : 16;
        int h = room.hasFloorplan() ? Math.max(1, room.planHeight()) : 16;
        int midX = w / 2;
        int midY = h / 2;
        for (int rad = 0; rad < Math.max(w, h); rad++) {
            for (int y = midY - rad; y <= midY + rad; y++) {
                for (int x = midX - rad; x <= midX + rad; x++) {
                    if (x < 0 || y < 0 || x >= w || y >= h) continue;
                    if (rad > 0 && Math.abs(x - midX) != rad && Math.abs(y - midY) != rad) continue;
                    if (!boundsOnlyFit(room, x, y, size)) continue;
                    HPoint p = new HPoint(x, y);
                    if (overlapsTaken(p, size, taken)) continue;
                    if (room.hasFloorItemAt(x, y)) continue;
                    return p;
                }
            }
        }
        return new HPoint(Math.max(0, midX - size / 2), Math.max(0, midY - size / 2));
    }

    private static boolean overlapsTaken(HPoint p, int size, List<HPoint> taken) {
        if (taken == null) return false;
        for (HPoint t : taken) {
            if (t == null) continue;
            if (Math.abs(t.getX() - p.getX()) < size && Math.abs(t.getY() - p.getY()) < size) return true;
        }
        return false;
    }

    public static HPoint findAsideSpot(RoomTracker room, int fromX, int fromY, int avoidX, int avoidY) {
        int[][] deltas = {
                {0, 2}, {2, 0}, {0, -2}, {-2, 0},
                {1, 2}, {2, 1}, {-1, 2}, {2, -1},
                {0, 3}, {3, 0}, {0, -3}, {-3, 0}
        };
        for (int[] d : deltas) {
            int x = fromX + d[0];
            int y = fromY + d[1];
            if (x == avoidX && y == avoidY) continue;
            if (isFloor(room, x, y)) return new HPoint(x, y);
        }
        return new HPoint(Math.max(0, fromX), Math.max(0, fromY + 1));
    }

    public static int dimensionForClass(String className) {
        String s = FurniCatalog.normalize(className);
        if (s.equals("tile_stackmagic") || s.contains("1x1")) return 1;
        if (s.equals("tile_stackmagic1") || s.contains("1x2") || s.contains("2x1")) return -1;
        if (s.equals("tile_stackmagic2") || s.contains("2x2")) return 2;
        if (s.contains("4x4")) return 4;
        if (s.contains("6x6")) return 6;
        if (s.contains("8x8")) return 8;
        return 0;
    }

    public static int dimensionForTypeId(int typeId) {
        int t = Math.abs(typeId);
        if (t == 5103 || t == 4803) return 1;
        if (t == 5180 || t == 4880) return -1;
        if (t == 5181 || t == 4881) return 2;
        if (t == 11804) return 4;
        if (t == 11803) return 6;
        if (t == 11805) return 8;
        return 0;
    }
}
