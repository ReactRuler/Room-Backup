package roommount.core;

import gearth.extensions.ExtensionForm;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HPoint;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class MountEngine {
    private static final double Z_EPS = 0.08;

    private final ExtensionForm ext;
    private final RoomTracker room;
    private final InventoryTracker inventory;
    private final FurniCatalog catalog;
    private final Consumer<String> log;
    private volatile boolean busy;
    private volatile boolean stop;
    private volatile Models.MountOptions activeOpt;

    public MountEngine(ExtensionForm ext, RoomTracker room, InventoryTracker inventory, FurniCatalog catalog, Consumer<String> log) {
        this.ext = ext;
        this.room = room;
        this.inventory = inventory;
        this.catalog = catalog;
        this.log = log;
    }

    private int dly() {
        Models.MountOptions o = activeOpt;
        return o != null ? o.liveDelay() : 320;
    }

    private int dlyScaled(int baseAt320) {
        int d = dly();
        return Math.max(20, (int) Math.round(baseAt320 * (d / 320.0)));
    }

    public boolean isBusy() {
        return busy;
    }

    public void stop() {
        stop = true;
    }

    public void mount(List<Models.Target> targets, Models.MountOptions opt) {
        if (busy) {
            log.accept("Busy - press Stop or type :mstop");
            return;
        }
        busy = true;
        stop = false;
        activeOpt = opt;
        new Thread(() -> {
            try {
                runMount(targets, opt);
            } catch (Exception ex) {
                log.accept("ERROR " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
                ex.printStackTrace();
            } finally {
                busy = false;
                activeOpt = null;
            }
        }, "room-mount").start();
    }

    private void runMount(List<Models.Target> targets, Models.MountOptions opt) {
        int moved = 0, skipped = 0, placedN = 0, missing = 0, failed = 0;
        List<Models.Target> ordered = new ArrayList<>(targets);
        ordered.sort(Comparator
                .comparingInt((Models.Target t) -> "wall".equals(t.type) ? 0 : 1)
                .thenComparingDouble(t -> "floor".equals(t.type) ? t.z : 0)
                .thenComparingInt(t -> t.y)
                .thenComparingInt(t -> t.x));
        log.accept("Mounting " + ordered.size() + " items (walls first like G-BuildTools, delay=" + dly()
                + "ms, force=" + opt.force + ")");
        for (Models.Target t : ordered) {
            if ("floor".equals(t.type)) {
                log.accept("target floor " + t.className + " @" + t.x + "," + t.y + ",z" + t.z + " rot=" + t.rotation);
            } else {
                log.accept("target wall " + t.className + " " + t.wallPos);
            }
        }

        Map<String, List<HFloorItem>> floorPools = new HashMap<>();
        for (HFloorItem f : room.floorItems()) {
            String cn = classOfFloor(f);
            if (cn.isEmpty() || isStackMagicItem(f)) continue;
            floorPools.computeIfAbsent(cn.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(f);
        }
        Map<String, List<HWallItem>> wallPools = new HashMap<>();
        for (HWallItem w : room.wallItems()) {
            String cn = classOfWall(w);
            if (cn.isEmpty()) continue;
            wallPools.computeIfAbsent(cn.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(w);
        }

        Set<Integer> usedWall = new HashSet<>();
        Set<Integer> usedFloor = new HashSet<>();
        log.accept("Room Backup 1.0.0 - place-new stages aside then stackmagic");

        for (Models.Target t : ordered) {
            if (stop) break;
            if (!"wall".equals(t.type)) continue;
            HWallItem cand = pickWall(wallPools, t, usedWall);
            if (cand == null) {
                if (opt.missingMode == Models.MissingMode.SKIP) {
                    missing++;
                    log.accept("MISSING wall " + t.className + " " + t.wallPos + " (skip)");
                    continue;
                }
                if (opt.missingMode == Models.MissingMode.STOP) {
                    missing++;
                    log.accept("MISSING wall " + t.className + " " + t.wallPos + " (stop)");
                    break;
                }
                int newId = placeMissingWall(t, opt);
                if (newId < 0) {
                    missing++;
                    log.accept("MISSING wall " + t.className + " " + t.wallPos);
                    continue;
                }
                placedN++;
                cand = room.wallById(newId);
                if (cand == null) {
                    failed++;
                    continue;
                }
            }
            usedWall.add(cand.getId());
            removeWallPool(wallPools, t.className, cand.getId());
            String cur = cand.getLocation() == null ? "" : cand.getLocation();
            String want = WallFurniInfo.normalize(t.wallPos);
            String liveWallState = cand.getState() == null ? "" : cand.getState();
            String wantWallState = t.state == null ? "" : t.state;
            boolean posOk = !want.isEmpty() && WallFurniInfo.samePos(cur, want);
            boolean stateOk = statesEqual(liveWallState, wantWallState);
            if (!opt.force && posOk && stateOk) {
                skipped++;
                log.accept("skip wall #" + cand.getId() + " " + t.className + " already " + want);
                continue;
            }
            if (moveWall(cand.getId(), want, dly())) {
                moved++;
                log.accept("OK wall " + t.className + " #" + cand.getId());
            } else {
                failed++;
                log.accept("FAIL wall " + t.className + " #" + cand.getId() + " pos=" + want);
            }
        }

        boolean anyFloor = false;
        for (Models.Target t : ordered) {
            if ("floor".equals(t.type) && !FurniCatalog.isStackMagic(t.className)
                    && !FurniCatalog.isStackMagicTypeId(t.kind)) {
                anyFloor = true;
                break;
            }
        }
        if (!anyFloor || stop) {
            log.accept("Done - moved " + moved + " | skipped " + skipped + " | placed " + placedN
                    + " | missing " + missing + " | failed " + failed);
            return;
        }

        room.forceRefreshFloor(2000);
        floorPools.clear();
        for (HFloorItem f : room.floorItems()) {
            String cn = classOfFloor(f);
            if (cn.isEmpty() || isStackMagicItem(f)) continue;
            floorPools.computeIfAbsent(cn.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(f);
        }
        log.accept("floor pool rebuilt after refresh: " + room.floorItems().size() + " floor items");

        List<HFloorItem> allStackTiles = collectStackTiles();
        if (allStackTiles.isEmpty()) {
            log.accept("Still 0 stackmagic - if you see one in-game: pick it up and place it again now");
            sleep(1500);
            allStackTiles = collectStackTiles();
        }
        if (allStackTiles.isEmpty()) {
            log.accept("Trying inventory place...");
            try {
                if (ensureStackMagicInRoom(dly())) {
                    allStackTiles = collectStackTiles();
                }
            } catch (Throwable ex) {
                log.accept("inventory/stack spawn error: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
            }
        }
        List<StackTileHelper.Info> stackInfos = buildStackInfosExact(allStackTiles);
        log.accept("stackmagic tiles=" + allStackTiles.size() + " infos=" + stackInfos.size()
                + " wallItems=" + room.wallItems().size() + " floorplan=" + room.hasFloorplan());
        for (HFloorItem f : allStackTiles) {
            int[] ts = sizeOfStackItem(f);
            log.accept("candidate stack #" + f.getId() + " kind=" + f.getTypeId() + " " + classOfFloor(f) + " " + ts[0] + "x" + ts[1]);
        }
        if (stackInfos.isEmpty()) {
            log.accept("ABORT floors - no Magic Stack Tile. Walls already done.");
            log.accept("Done - moved " + moved + " | skipped " + skipped + " | placed " + placedN
                    + " | missing " + missing + " | failed " + failed);
            return;
        }
        log.accept("Clearing stack tiles off furniture (height=0 -> park)");
        parkAllStackTiles(allStackTiles, stackInfos, dly());
        StackTileHelper.Info preferBig = null;
        for (StackTileHelper.Info si : stackInfos) {
            if (si.dimension >= 4) { preferBig = si; break; }
        }
        if (preferBig == null) {
            for (StackTileHelper.Info si : stackInfos) {
                if (si.dimension == 2) { preferBig = si; break; }
            }
        }
        if (preferBig != null) {
            log.accept("largest stack ready #" + preferBig.furniId + " dim=" + preferBig.dimension);
        }
        preflightStackMove(preferBig != null ? preferBig : stackInfos.get(0), dly());

        List<Models.Target> floorFailRetry = new ArrayList<>();
        List<Integer> floorFailIds = new ArrayList<>();

        for (Models.Target t : ordered) {
            if (stop) {
                log.accept("Stopped");
                break;
            }
            if (!"floor".equals(t.type)) continue;
            if (FurniCatalog.isStackMagic(t.className) || FurniCatalog.isStackMagicTypeId(t.kind)) {
                continue;
            }

            HFloorItem candF = pickFloor(floorPools, t, usedFloor);
            boolean justPlaced = false;
            if (candF == null) {
                if (opt.missingMode == Models.MissingMode.SKIP) {
                    missing++;
                    log.accept("MISSING floor " + t.className + " @" + t.x + "," + t.y + " (skip)");
                    continue;
                }
                if (opt.missingMode == Models.MissingMode.STOP) {
                    missing++;
                    log.accept("MISSING floor " + t.className + " @" + t.x + "," + t.y + " (stop)");
                    break;
                }
                int newId = placeMissingFloor(t, opt);
                if (newId < 0) {
                    missing++;
                    log.accept("MISSING floor " + t.className + " @" + t.x + "," + t.y + " (id " + t.id + " not in room)");
                    continue;
                }
                placedN++;
                justPlaced = true;
                log.accept("placed missing " + t.className + " newId=" + newId + " (saved id was " + t.id + ")");
                candF = room.floorById(newId);
                if (candF == null) {
                    failed++;
                    continue;
                }
            }
            usedFloor.add(candF.getId());
            removeFloorPool(floorPools, t.className, candF.getId());

            HPoint tile = candF.getTile();
            int dir = candF.getFacing() == null ? 0 : candF.getFacing().ordinal();
            String liveState = floorStateOf(candF);
            String wantState = t.state == null ? "" : t.state;
            boolean posSame = tile.getX() == t.x && tile.getY() == t.y
                    && dir == t.rotation
                    && Math.abs(tile.getZ() - t.z) <= Z_EPS;
            boolean stateSame = statesEqual(liveState, wantState);
            if (!opt.force && !justPlaced && posSame && stateSame) {
                skipped++;
                log.accept("skip #" + candF.getId() + " " + t.className
                        + " already @" + t.x + "," + t.y + ",z" + t.z
                        + (wantState.isEmpty() ? "" : " state=" + wantState));
                continue;
            }
            if (opt.force) {
                log.accept("force remount " + t.className + " #" + candF.getId());
            } else if (justPlaced) {
                log.accept("stack-mount new #" + candF.getId() + " " + t.className
                        + " from @" + tile.getX() + "," + tile.getY() + ",z" + tile.getZ()
                        + " -> @" + t.x + "," + t.y + ",z" + t.z);
            } else if (candF.getId() == t.id) {
                log.accept("fix #" + t.id + " " + t.className
                        + " @" + tile.getX() + "," + tile.getY() + ",z" + tile.getZ()
                        + " state=" + liveState
                        + " -> @" + t.x + "," + t.y + ",z" + t.z
                        + " state=" + wantState);
            } else {
                log.accept("id " + t.id + " gone, using live#" + candF.getId() + " " + t.className
                        + " @" + tile.getX() + "," + tile.getY() + ",z" + tile.getZ()
                        + " -> @" + t.x + "," + t.y + ",z" + t.z);
            }

            boolean ok = true;
            if (opt.force || justPlaced || !posSame) {
                ok = moveFloorGPresetsExact(t, candF, stackInfos);
            }
            if (ok && (opt.force || justPlaced || !stateSame)) {
                if (!ensureFloorState(candF.getId(), wantState)) {
                    ok = false;
                    log.accept("FAIL state " + t.className + " #" + candF.getId()
                            + " want=" + wantState + " got=" + floorStateOf(room.floorById(candF.getId())));
                }
            }
            if (ok) {
                moved++;
            } else {
                failed++;
                floorFailRetry.add(t);
                floorFailIds.add(candF.getId());
                log.accept("FAIL floor " + t.className + " #" + candF.getId() + " @" + t.x + "," + t.y + ",z" + t.z);
            }
        }

        if (!stop && !floorFailRetry.isEmpty()) {
            log.accept("RETRY pass for " + floorFailRetry.size() + " failed floor item(s)…");
            sleep(Math.max(400, dly() * 2));
            parkAllStackTiles(allStackTiles, stackInfos, dly());
            preflightStackMove(preferBig != null ? preferBig : stackInfos.get(0), dly());
            for (int i = 0; i < floorFailRetry.size(); i++) {
                if (stop) break;
                Models.Target t = floorFailRetry.get(i);
                int fid = floorFailIds.get(i);
                HFloorItem candF = room.floorById(fid);
                if (candF == null) {
                    log.accept("RETRY skip missing #" + fid + " " + t.className);
                    continue;
                }
                HPoint tile = candF.getTile();
                int dir = candF.getFacing() == null ? 0 : candF.getFacing().ordinal();
                String liveState = floorStateOf(candF);
                String wantState = t.state == null ? "" : t.state;
                boolean posSame = tile.getX() == t.x && tile.getY() == t.y
                        && dir == t.rotation
                        && Math.abs(tile.getZ() - t.z) <= Z_EPS;
                boolean stateSame = statesEqual(liveState, wantState);
                if (!opt.force && posSame && stateSame) {
                    failed = Math.max(0, failed - 1);
                    skipped++;
                    log.accept("RETRY already ok #" + fid + " " + t.className);
                    continue;
                }
                log.accept("RETRY #" + fid + " " + t.className
                        + " @" + tile.getX() + "," + tile.getY() + ",z" + tile.getZ()
                        + " state=" + liveState
                        + " -> @" + t.x + "," + t.y + ",z" + t.z
                        + " state=" + wantState);
                boolean ok = true;
                if (opt.force || !posSame) {
                    ok = moveFloorGPresetsExact(t, candF, stackInfos);
                }
                if (ok && (opt.force || !stateSame)) {
                    ok = ensureFloorState(fid, wantState);
                }
                if (ok) {
                    failed = Math.max(0, failed - 1);
                    moved++;
                    log.accept("RETRY OK #" + fid + " " + t.className);
                } else {
                    log.accept("RETRY FAIL #" + fid + " " + t.className);
                }
            }
        }

        parkAllStackTiles(allStackTiles, stackInfos, dly());

        log.accept("Done - moved " + moved + " | skipped " + skipped + " | placed " + placedN + " | missing " + missing + " | failed " + failed);
        if (moved == 0 && failed == 0 && missing == 0 && skipped > 0) {
            log.accept("Nothing to move - room already matches snapshot.");
            log.accept("Move furniture out of place, OR tick Force remount, OR :msave again with the build you want.");
        }
    }

    private boolean moveFloorGPresetsExact(Models.Target t, HFloorItem cand, List<StackTileHelper.Info> stackInfos) {
        int itemId = cand.getId();
        if (stackInfos.isEmpty()) {
            log.accept("NO stackmagic - refusing plain move (would break overlap)");
            return false;
        }

        int[] need = footprintOf(t);
        int needW = Math.max(1, need[0]);
        int needH = Math.max(1, need[1]);

        List<StackTileHelper.Info> ranked = new ArrayList<>(stackInfos);
        ranked.sort(Comparator
                .comparingInt((StackTileHelper.Info a) ->
                        StackTileHelper.coversFootprint(needW, needH, a.width(), a.height()) ? 0 : 1)
                .thenComparingInt((StackTileHelper.Info a) -> {
                    boolean exact = (a.width() == needW && a.height() == needH)
                            || (a.width() == needH && a.height() == needW);
                    return exact ? 0 : 1;
                })
                .thenComparingInt((StackTileHelper.Info a) -> -StackTileHelper.area(a.width(), a.height())));

        StackTileHelper.Info stackInfo = StackTileHelper.findBestDropLocation(
                t.x, t.y, needW, needH, ranked, room);
        if (stackInfo == null || stackInfo.drop == null) {
            for (StackTileHelper.Info si : ranked) {
                if (!StackTileHelper.coversFootprint(needW, needH, si.width(), si.height())) continue;
                stackInfo = StackTileHelper.forceCoverDrop(si, t.x, t.y, needW, needH, room);
                if (stackInfo != null && stackInfo.drop != null) break;
            }
        }
        if (stackInfo == null || stackInfo.drop == null) {
            for (StackTileHelper.Info si : ranked) {
                stackInfo = StackTileHelper.forceCoverDrop(si, t.x, t.y, 1, 1, room);
                if (stackInfo != null && stackInfo.drop != null) {
                    log.accept("stack fallback cover-point only (need " + needW + "x" + needH
                            + " have dim=" + si.dimension + ")");
                    break;
                }
            }
        }
        if (stackInfo == null || stackInfo.drop == null) {
            log.accept("STACK SKIP - no stack tile under @" + t.x + "," + t.y);
            return false;
        }
        log.accept("stack pick dim=" + stackInfo.dimension + " origin(top)="
                + stackInfo.drop.getX() + "," + stackInfo.drop.getY()
                + " covers @" + t.x + "," + t.y + " size " + needW + "x" + needH
                + " " + t.className + " rate=" + dly() + "ms");

        int tileId = stackInfo.furniId;
        int habboZ = (int) Math.round(t.z * 100.0);
        int sw = stackInfo.dimension == -1 ? (stackInfo.dropRot == 2 ? 1 : 2) : Math.max(1, stackInfo.dimension);
        int sh = stackInfo.dimension == -1 ? (stackInfo.dropRot == 2 ? 2 : 1) : Math.max(1, stackInfo.dimension);

        HPoint cur = cand.getTile();
        int curRot = cand.getFacing() == null ? 0 : cand.getFacing().ordinal();
        HPoint aside = StackTileHelper.findAsideSpot(room, cur.getX(), cur.getY(), t.x, t.y);
        log.accept("clear aside #" + itemId + " -> " + aside.getX() + "," + aside.getY());
        sendMove(itemId, aside.getX(), aside.getY(), curRot);
        sleep(dlyScaled(140));

        List<int[]> tries = new ArrayList<>();
        tries.add(new int[]{stackInfo.drop.getX(), stackInfo.drop.getY(), stackInfo.dropRot});
        for (int[] o : StackTileHelper.validOriginsCoveringFurni(
                t.x, t.y, Math.min(needW, sw), Math.min(needH, sh), sw, sh, stackInfo.dropRot, room)) {
            boolean dup = false;
            for (int[] t0 : tries) {
                if (t0[0] == o[0] && t0[1] == o[1] && t0[2] == o[2]) {
                    dup = true;
                    break;
                }
            }
            if (!dup) tries.add(o);
        }
        for (int[] o : StackTileHelper.validOriginsCoveringFurni(
                t.x, t.y, 1, 1, sw, sh, stackInfo.dropRot, room)) {
            boolean dup = false;
            for (int[] t0 : tries) {
                if (t0[0] == o[0] && t0[1] == o[1] && t0[2] == o[2]) {
                    dup = true;
                    break;
                }
            }
            if (!dup) tries.add(o);
        }

        boolean tilePlaced = false;
        int dx = tries.get(0)[0], dy = tries.get(0)[1], drot = tries.get(0)[2];
        for (int[] o : tries) {
            if (stop) break;
            dx = o[0];
            dy = o[1];
            drot = o[2];
            if (confirmStackAt(tileId, dx, dy, drot, 4)) {
                tilePlaced = true;
                break;
            }
            log.accept("stack not at drop after burst -> " + dx + "," + dy);
        }
        if (!tilePlaced) {
            log.accept("STACK FAILED under @" + t.x + "," + t.y + " - restoring furni xy only");
            sendMove(itemId, t.x, t.y, t.rotation);
            sleep(dlyScaled(120));
            return false;
        }

        boolean zOk = false;
        for (int attempt = 0; attempt < 5 && !stop; attempt++) {
            if (!confirmStackAt(tileId, dx, dy, drot, 2)) {
                log.accept("stack drifted before height - re-seat");
                if (!confirmStackAt(tileId, dx, dy, drot, 4)) break;
            }
            setStackHeight(tileId, habboZ);
            sleep(dlyScaled(160));
            sendMove(itemId, t.x, t.y, t.rotation);
            sleep(dlyScaled(220));

            HFloorItem after = room.floorById(itemId);
            if (after == null) {
                zOk = true;
                break;
            }
            HPoint a = after.getTile();
            boolean xyOk = a.getX() == t.x && a.getY() == t.y;
            zOk = Math.abs(a.getZ() - t.z) <= 0.35;
            log.accept(t.className + " #" + itemId + " got @" + a.getX() + "," + a.getY() + ",z" + a.getZ()
                    + " want z=" + t.z + (xyOk && zOk ? " OK" : (xyOk ? " XY-ok Z-BAD try" + (attempt + 1) : " BAD")));
            if (xyOk && zOk) break;
            if (!xyOk) {
                sendMove(itemId, aside.getX(), aside.getY(), t.rotation);
                sleep(dlyScaled(120));
            } else if (!zOk) {
                setStackHeight(tileId, habboZ);
                sleep(dlyScaled(200));
                sendMove(itemId, aside.getX(), aside.getY(), t.rotation);
                sleep(dlyScaled(100));
                sendMove(itemId, t.x, t.y, t.rotation);
                sleep(dlyScaled(220));
                after = room.floorById(itemId);
                if (after != null) {
                    a = after.getTile();
                    zOk = a.getX() == t.x && a.getY() == t.y && Math.abs(a.getZ() - t.z) <= 0.35;
                    if (zOk) {
                        log.accept(t.className + " #" + itemId + " Z recovered @" + a.getZ());
                        break;
                    }
                }
            }
        }

        parkOneStackTile(stackInfo);
        return zOk || Math.abs(t.z) <= 0.05;
    }

    private boolean confirmStackAt(int tileId, int x, int y, int rot, int bursts) {
        for (int i = 0; i < bursts && !stop; i++) {
            log.accept("STACK MOVE #" + tileId + " -> " + x + "," + y + " rot=" + rot + " burst=" + (i + 1));
            sendMove(tileId, x, y, rot);
            sleep(dlyScaled(160 + i * 40));
            HFloorItem liveTile = room.floorById(tileId);
            if (liveTile == null) continue;
            HPoint tp = liveTile.getTile();
            if (tp.getX() == x && tp.getY() == y) return true;
        }
        return false;
    }

    private void setStackHeight(int tileId, int habboZ) {
        log.accept("SET HEIGHT #" + tileId + " habbo=" + habboZ + " (z=" + (habboZ / 100.0) + ")");
        int burst = Math.max(3, dly() < 250 ? 5 : 4);
        for (int i = 0; i < burst; i++) {
            try {
                HPacket p = new HPacket("SetCustomStackingHeight", HMessage.Direction.TOSERVER, tileId, habboZ);
                boolean sent = ext.sendToServer(p);
                if (!sent || p.isCorrupted()) {
                    log.accept("SetCustomStackingHeight bad sent=" + sent + " corrupted=" + p.isCorrupted());
                }
            } catch (Exception ex) {
                log.accept("SetCustomStackingHeight fail: " + ex.getMessage());
            }
            sleep(Math.max(50, dly() / 3));
        }
        try {
            HPacket p2 = new HPacket("StackingHelperSetCaretHeight", HMessage.Direction.TOSERVER, tileId, habboZ);
            if (!p2.isCorrupted()) {
                ext.sendToServer(p2);
            }
        } catch (Exception ignored) {
        }
        sleep(Math.max(100, dly()));
    }

    private static String floorStateOf(HFloorItem f) {
        if (f == null) return "";
        try {
            if (f.getStuff() == null) return "";
            String legacy = f.getStuff().getLegacyString();
            return legacy == null ? "" : legacy;
        } catch (Exception ex) {
            return "";
        }
    }

    private static boolean statesEqual(String a, String b) {
        String aa = a == null ? "" : a.trim();
        String bb = b == null ? "" : b.trim();
        return aa.equals(bb);
    }

    private boolean ensureFloorState(int itemId, String wantRaw) {
        String want = wantRaw == null ? "" : wantRaw.trim();
        if (want.isEmpty()) return true;
        HFloorItem live = room.floorById(itemId);
        if (live == null) return false;
        String cur = floorStateOf(live);
        if (statesEqual(cur, want)) return true;

        try {
            int asInt = Integer.parseInt(want);
            log.accept("SET STATE wired -110 #" + itemId + " -> " + want);
            ext.sendToServer(new HPacket("WiredSetObjectVariableValue", HMessage.Direction.TOSERVER,
                    0, itemId, "-110", asInt));
            sleep(Math.max(80, dly()));
            live = room.floorById(itemId);
            if (live != null && statesEqual(floorStateOf(live), want)) {
                log.accept("state OK wired #" + itemId + " =" + want);
                return true;
            }
        } catch (NumberFormatException ignored) {
        } catch (Exception ex) {
            log.accept("wired state fail: " + ex.getMessage());
        }

        String prev = "-1";
        for (int i = 0; i < 20 && !stop; i++) {
            live = room.floorById(itemId);
            if (live == null) return false;
            cur = floorStateOf(live);
            if (statesEqual(cur, want)) {
                log.accept("state OK click #" + itemId + " =" + want + " after " + (i) + " use(s)");
                return true;
            }
            if (i > 0 && statesEqual(cur, prev)) {
                log.accept("state stuck #" + itemId + " at " + cur + " want " + want);
                break;
            }
            prev = cur;
            log.accept("UseFurniture #" + itemId + " state " + cur + " -> want " + want + " try=" + (i + 1));
            try {
                ext.sendToServer(new HPacket("UseFurniture", HMessage.Direction.TOSERVER, itemId, 0));
            } catch (Exception ex) {
                try {
                    ext.sendToServer(new HPacket("UseStuff", HMessage.Direction.TOSERVER, itemId, 0));
                } catch (Exception ex2) {
                    log.accept("UseFurniture fail: " + ex2.getMessage());
                    return false;
                }
            }
            sleep(Math.max(80, dly()));
        }
        live = room.floorById(itemId);
        return live != null && statesEqual(floorStateOf(live), want);
    }

    private void parkOneStackTile(StackTileHelper.Info info) {
        if (info == null) return;
        sendMove(info.furniId, info.home.getX(), info.home.getY(), info.homeRot);
        sleep(dlyScaled(80));
        setStackHeight(info.furniId, 0);
    }

    private void parkAllStackTiles(List<HFloorItem> allStackTiles, List<StackTileHelper.Info> stackInfos, int ignored) {
        Set<Integer> parked = new HashSet<>();
        for (StackTileHelper.Info info : stackInfos) {
            parked.add(info.furniId);
            parkOneStackTile(info);
        }
        int ox = 0;
        for (HFloorItem f : allStackTiles) {
            if (f == null || parked.contains(f.getId())) continue;
            StackTileHelper.Info tmp = new StackTileHelper.Info(f.getId(), new HPoint(ox, 0), 0, resolveStackDim(f));
            parkOneStackTile(tmp);
            ox += 2;
            log.accept("park leftover stack #" + f.getId() + " height=0 @" + tmp.home.getX() + "," + tmp.home.getY());
        }
    }

    private boolean ensureStackMagicInRoom(int delayMs) {
        waitInventory(4000);
        int[] typeIds = stackMagicTypeIds();
        for (int typeId : typeIds) {
            int available = inventory.floorCount(typeId);
            log.accept("inventory stack kind=" + typeId + " count=" + available
                    + " class=" + catalog.floorClass(typeId));
            if (available <= 0) continue;
            HInventoryItem invItem = inventory.takeFloor(typeId);
            if (invItem == null) continue;
            Set<Integer> before = new HashSet<>();
            for (HFloorItem f : room.floorItems()) before.add(f.getId());
            int invId = invItem.getId();
            int x = 0, y = 0;
            log.accept("INV place stack #" + invId + " kind=" + typeId + " @" + x + "," + y);
            try {
                ext.sendToServer(new HPacket("PlaceObject", HMessage.Direction.TOSERVER,
                        String.format("-%d %d %d %d", invId, x, y, 0)));
            } catch (Exception ex) {
                log.accept("PlaceObject fail: " + ex.getMessage());
                continue;
            }
            sleep(delayMs + 300);
            for (int i = 0; i < 10; i++) {
                for (HFloorItem f : room.floorItems()) {
                    if (before.contains(f.getId())) continue;
                    if (isStackMagicItem(f) || f.getId() == invId || Math.abs(f.getTypeId()) == typeId) {
                        log.accept("Stack tile now in room #" + f.getId() + " " + classOfFloor(f)
                                + " @" + f.getTile().getX() + "," + f.getTile().getY());
                        return true;
                    }
                }
                sleep(150);
            }
            log.accept("PlaceObject sent but stack not seen in room yet");
        }
        try {
            for (String cn : new String[]{"tile_stackmagic", "tile_stackmagic2", "tile_stackmagic1"}) {
                Models.FurniMeta meta = catalog.floorByClass(cn);
                int offer = meta != null && meta.offerId > 0 ? meta.offerId : knownStackTypeId(cn);
                Set<Integer> before = new HashSet<>();
                for (HFloorItem f : room.floorItems()) before.add(f.getId());
                log.accept("BC place fallback " + cn + " offer=" + offer);
                ext.sendToServer(new HPacket("BuildersClubPlaceRoomItem", HMessage.Direction.TOSERVER,
                        -1, offer, "", 0, 0, 0));
                sleep(delayMs + 250);
                for (HFloorItem f : room.floorItems()) {
                    if (before.contains(f.getId())) continue;
                    if (isStackMagicItem(f)) {
                        log.accept("BC stack in room #" + f.getId());
                        return true;
                    }
                }
            }
        } catch (Exception ex) {
            log.accept("BC fallback fail: " + ex.getMessage());
        }
        return false;
    }

    private int[] stackMagicTypeIds() {
        List<Integer> ids = new ArrayList<>();
        for (int t : new int[]{4803, 4880, 4881, 5103, 5180, 5181, 11803, 11804, 11805}) {
            ids.add(t);
        }
        for (String cn : FurniCatalog.STACK_MAGIC_CLASSES) {
            int tid = catalog.typeIdForClass(cn);
            if (tid > 0 && !ids.contains(tid)) ids.add(tid);
        }
        int[] out = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) out[i] = ids.get(i);
        return out;
    }

    private void waitInventory(int timeoutMs) {
        if (inventory.getState() == InventoryTracker.State.LOADED) return;
        log.accept("Requesting inventory...");
        inventory.request();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (inventory.getState() == InventoryTracker.State.LOADED) return;
            sleep(100);
        }
        log.accept("Inventory wait timeout state=" + inventory.getState());
    }

    private boolean spawnStackTileBc(int delayMs) {
        return ensureStackMagicInRoom(delayMs);
    }

    public void testStackMove(int delayMs) {
        if (busy) {
            log.accept("Busy - :mstop first");
            return;
        }
        busy = true;
        stop = false;
        try {
            log.accept("=== STACK TEST 1.1.3 ===");
            room.ensureFloorLoaded(2000);
            List<HFloorItem> tiles = collectStackTiles();
            if (tiles.isEmpty()) {
                log.accept("0 stackmagic in room - trying inventory...");
                ensureStackMagicInRoom(delayMs);
                tiles = collectStackTiles();
            }
            log.accept("detected stackmagic=" + tiles.size() + " floorTotal=" + room.floorItems().size());
            if (tiles.isEmpty()) {
                log.accept("STILL 0 - place Magic Stack Tile from catalog into the room, then :mstack");
                return;
            }
            HFloorItem tile = tiles.get(0);
            HPoint p = tile.getTile();
            int rot = tile.getFacing() == null ? 0 : tile.getFacing().ordinal();
            int x1 = p.getX();
            int y1 = p.getY();
            int x2 = x1 + 1;
            int y2 = y1;
            log.accept("Moving stack #" + tile.getId() + " " + x1 + "," + y1 + " -> " + x2 + "," + y2);
            sendMove(tile.getId(), x2, y2, rot);
            sleep(Math.max(400, delayMs));
            try {
                ext.sendToServer(new HPacket("SetCustomStackingHeight", HMessage.Direction.TOSERVER, tile.getId(), 0));
            } catch (Exception ex) {
                log.accept("height fail: " + ex.getMessage());
            }
            sleep(200);
            log.accept("Moving stack #" + tile.getId() + " back -> " + x1 + "," + y1);
            sendMove(tile.getId(), x1, y1, rot);
            sleep(Math.max(400, delayMs));
            log.accept("STACK TEST done - if the tile did not move, reconnect Room Backup in G-Earth");
        } finally {
            busy = false;
        }
    }

    private void preflightStackMove(StackTileHelper.Info info, int ignored) {
        HFloorItem live = room.floorById(info.furniId);
        if (live == null) return;
        HPoint p = live.getTile();
        int rot = live.getFacing() == null ? 0 : live.getFacing().ordinal();
        int nx = p.getX() + 1;
        int ny = p.getY();
        log.accept("PREFLIGHT stack #" + info.furniId + " " + p.getX() + "," + p.getY() + " -> " + nx + "," + ny);
        sendMove(info.furniId, nx, ny, rot);
        sleep(dlyScaled(200));
        sendMove(info.furniId, p.getX(), p.getY(), rot);
        sleep(dlyScaled(150));
        log.accept("PREFLIGHT done - tile should have hopped");
    }

    private List<StackTileHelper.Info> buildStackInfosExact(List<HFloorItem> allStackTiles) {
        List<StackTileHelper.Info> infos = new ArrayList<>();
        List<HFloorItem> ranked = new ArrayList<>(allStackTiles);
        ranked.sort(Comparator.comparingInt((HFloorItem f) -> {
            int dim = resolveStackDim(f);
            int size = dim == -1 ? 2 : Math.max(1, dim);
            return -(size * size);
        }));

        List<HPoint> taken = new ArrayList<>();
        for (HFloorItem f : ranked) {
            int dim = resolveStackDim(f);
            HPoint home = StackTileHelper.findParkSpot(room, dim, taken);
            if (home == null) {
                home = f.getTile();
                log.accept("no free park for stack #" + f.getId() + " dim=" + dim + " - keep @"
                        + home.getX() + "," + home.getY());
            } else {
                taken.add(home);
            }
            int rot = f.getFacing() == null ? 0 : f.getFacing().ordinal();
            infos.add(new StackTileHelper.Info(f.getId(), home, rot, dim));
            log.accept("stack info #" + f.getId() + " dim=" + dim
                    + " park(top)=" + home.getX() + "," + home.getY());
        }
        return infos;
    }

    private int resolveStackDim(HFloorItem f) {
        int dim = StackTileHelper.dimensionForClass(classOfFloor(f));
        if (dim == 0) dim = StackTileHelper.dimensionForTypeId(f.getTypeId());
        if (dim == 0) {
            int[] ts = sizeOfStackItem(f);
            if (ts[0] == 2 && ts[1] == 2) dim = 2;
            else if (ts[0] == 1 && ts[1] == 1) dim = 1;
            else if (Math.max(ts[0], ts[1]) == 2 && Math.min(ts[0], ts[1]) == 1) dim = -1;
            else dim = 2;
        }
        return dim;
    }

    private List<HFloorItem> collectStackTiles() {
        List<HFloorItem> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();

        for (HFloorItem f : room.floorItems()) {
            if (f == null || !FurniCatalog.isStackMagicTypeId(f.getTypeId())) continue;
            if (!seen.add(f.getId())) continue;
            out.add(f);
            log.accept("stack hit #" + f.getId() + " kind=" + f.getTypeId()
                    + " " + classOfFloor(f) + " @" + f.getTile().getX() + "," + f.getTile().getY());
        }

        if (!out.isEmpty()) return out;

        for (String cn : FurniCatalog.STACK_MAGIC_CLASSES) {
            Set<Integer> typeIds = new HashSet<>();
            int fromCatalog = catalog.typeIdForClass(cn);
            if (fromCatalog > 0) typeIds.add(fromCatalog);
            int known = knownStackTypeId(cn);
            if (known > 0) typeIds.add(known);
            log.accept("lookup " + cn + " typeIds=" + typeIds);
            for (HFloorItem f : room.floorItems()) {
                if (f == null || seen.contains(f.getId())) continue;
                int tid = Math.abs(f.getTypeId());
                boolean match = typeIds.contains(tid)
                        || (f.getStaticClass() != null && FurniCatalog.normalize(f.getStaticClass()).equals(cn));
                if (!match) continue;
                seen.add(f.getId());
                out.add(f);
                log.accept("stack hit #" + f.getId() + " kind=" + f.getTypeId() + " via " + cn);
            }
        }
        return out;
    }

    private static int knownStackTypeId(String cn) {
        if ("tile_stackmagic".equals(cn)) return 4803;
        if ("tile_stackmagic1".equals(cn)) return 4880;
        if ("tile_stackmagic2".equals(cn)) return 4881;
        if ("tile_stackmagic4x4".equals(cn)) return 11804;
        if ("tile_stackmagic6x6".equals(cn)) return 11803;
        if ("tile_stackmagic8x8".equals(cn)) return 11805;
        return 0;
    }

    private boolean isStackMagicItem(HFloorItem f) {
        if (f == null) return false;
        if (FurniCatalog.isStackMagicTypeId(f.getTypeId())) return true;
        if (f.getStaticClass() != null && FurniCatalog.isStackMagic(f.getStaticClass())) return true;
        String cn = classOfFloor(f);
        if (FurniCatalog.isStackMagic(cn)) return true;
        Models.FurniMeta meta = catalog.floorByType(Math.abs(f.getTypeId()));
        return meta != null && FurniCatalog.isStackMagic(meta.className);
    }

    private int[] sizeOfStackItem(HFloorItem f) {
        int[] ts = FurniCatalog.stackTileSize(classOfFloor(f));
        if (ts[0] > 0 && ts[1] > 0) return ts;
        if (f.getStaticClass() != null) {
            ts = FurniCatalog.stackTileSize(f.getStaticClass());
            if (ts[0] > 0 && ts[1] > 0) return ts;
        }
        int tid = Math.abs(f.getTypeId());
        if (tid == 5103 || tid == 4803) return new int[]{1, 1};
        if (tid == 5180 || tid == 4880) return new int[]{2, 1};
        if (tid == 5181 || tid == 4881) return new int[]{2, 2};
        if (tid == 11804) return new int[]{4, 4};
        if (tid == 11803) return new int[]{6, 6};
        if (tid == 11805) return new int[]{8, 8};
        return new int[]{2, 2};
    }

    private void sendMove(int itemId, int x, int y, int dir) {
        HPacket packet = new HPacket("MoveObject", HMessage.Direction.TOSERVER, itemId, x, y, dir);
        log.accept("MoveObject #" + itemId + " -> " + x + "," + y + " dir=" + dir
                + " corrupted=" + packet.isCorrupted());
        boolean sent = ext.sendToServer(packet);
        if (!sent) log.accept("sendToServer FAILED for MoveObject #" + itemId);
    }

    private boolean moveWall(int itemId, String wallPos, int delayMs) {
        String want = WallFurniInfo.normalize(wallPos);
        if (want.isEmpty()) return false;
        try {
            WallFurniInfo info = new WallFurniInfo(itemId, want);
            ext.sendToServer(new HPacket("MoveWallItem", HMessage.Direction.TOSERVER,
                    (int) info.getFurniId(), info.moveString()));
            sleep(delayMs);
            HWallItem after = room.wallById(itemId);
            if (after == null) return true;
            String got = after.getLocation() == null ? "" : after.getLocation();
            return WallFurniInfo.samePos(got, info.moveString());
        } catch (Exception ex) {
            log.accept("MoveWallItem fail: " + ex.getMessage());
            return false;
        }
    }

    private int placeMissingFloor(Models.Target t, Models.MountOptions opt) {
        boolean tryBc = opt.sourceMode == Models.SourceMode.BC_FIRST
                || opt.sourceMode == Models.SourceMode.BC_ONLY
                || opt.sourceMode == Models.SourceMode.INV_FIRST;
        boolean tryInv = opt.sourceMode == Models.SourceMode.INV_FIRST
                || opt.sourceMode == Models.SourceMode.INV_ONLY
                || opt.sourceMode == Models.SourceMode.BC_FIRST;
        if (opt.sourceMode == Models.SourceMode.BC_ONLY) tryInv = false;
        if (opt.sourceMode == Models.SourceMode.INV_ONLY) tryBc = false;

        int offer = t.offerId;
        Models.FurniMeta meta = catalog.floorByClass(t.className);
        if (meta == null) meta = catalog.floorByType(t.kind);
        if (offer <= 0 && meta != null) offer = meta.offerId;
        boolean bcOk = (t.bc || (meta != null && meta.bc)) && offer > 0;

        List<HPoint> stageSpots = stageSpotsForPlace(t);
        Set<Integer> before = snapshotFloorIds();

        if (tryBc && bcOk) {
            for (HPoint stage : stageSpots) {
                try {
                    log.accept("place BC floor " + t.className + " offer=" + offer
                            + " stage @" + stage.getX() + "," + stage.getY()
                            + " (then stack -> @" + t.x + "," + t.y + ",z" + t.z + ")");
                    ext.sendToServer(new HPacket("BuildersClubPlaceRoomItem", HMessage.Direction.TOSERVER,
                            -1, offer, "", stage.getX(), stage.getY(), t.rotation));
                    sleep(dly() + 120);
                    int id = newestFloor(t, before);
                    if (id >= 0) {
                        log.accept("placed BC #" + id + " at stage @" + stage.getX() + "," + stage.getY());
                        return id;
                    }
                } catch (Exception ex) {
                    log.accept("BC floor fail @" + stage.getX() + "," + stage.getY() + ": " + ex.getMessage());
                }
            }
        }

        if (tryInv) {
            try {
                HInventoryItem invItem = null;
                if (t.kind > 0) invItem = inventory.takeFloor(t.kind);
                if (invItem == null) {
                    Integer resolved = catalog.typeIdForClass(t.className);
                    if (resolved != null && resolved > 0) invItem = inventory.takeFloor(resolved);
                }
                if (invItem != null) {
                    int invId = invItem.getId();
                    for (HPoint stage : stageSpots) {
                        log.accept("place INV floor " + t.className + " #" + invId
                                + " stage @" + stage.getX() + "," + stage.getY()
                                + " (then stack -> @" + t.x + "," + t.y + ",z" + t.z + ")");
                        ext.sendToServer(new HPacket("PlaceObject", HMessage.Direction.TOSERVER,
                                String.format("-%d %d %d %d", invId, stage.getX(), stage.getY(), t.rotation)));
                        sleep(dly() + 120);
                        if (room.floorById(invId) != null) {
                            log.accept("placed INV #" + invId + " at stage");
                            return invId;
                        }
                        int id = newestFloor(t, before);
                        if (id >= 0) return id;
                    }
                }
            } catch (Exception ex) {
                log.accept("INV floor fail: " + ex.getMessage());
            }
        }
        return -1;
    }

    private List<HPoint> stageSpotsForPlace(Models.Target t) {
        List<HPoint> spots = new ArrayList<>();
        HPoint park = StackTileHelper.findParkSpot(room, 1, List.of());
        if (park != null) spots.add(park);
        HPoint aside = StackTileHelper.findAsideSpot(room, t.x, t.y, t.x, t.y);
        if (aside != null) {
            boolean dup = false;
            for (HPoint s : spots) {
                if (s.getX() == aside.getX() && s.getY() == aside.getY()) {
                    dup = true;
                    break;
                }
            }
            if (!dup) spots.add(aside);
        }
        int[][] extras = {
                {0, 0}, {1, 0}, {0, 1}, {2, 0}, {0, 2}, {3, 1}, {1, 3},
                {park != null ? park.getX() + 1 : 5, park != null ? park.getY() : 7},
                {5, 7}, {6, 7}, {7, 7}, {4, 7}
        };
        for (int[] e : extras) {
            int x = e[0];
            int y = e[1];
            if (x == t.x && y == t.y) continue;
            if (!StackTileHelper.isFloor(room, x, y)) continue;
            boolean dup = false;
            for (HPoint s : spots) {
                if (s.getX() == x && s.getY() == y) {
                    dup = true;
                    break;
                }
            }
            if (!dup) spots.add(new HPoint(x, y));
        }
        if (spots.isEmpty()) spots.add(new HPoint(Math.max(0, t.x), Math.max(0, t.y + 2)));
        return spots;
    }

    private int placeMissingWall(Models.Target t, Models.MountOptions opt) {
        if (opt.sourceMode == Models.SourceMode.BC_ONLY) {
            log.accept("wall place skipped - no BC wall (G-BuildTools uses PlaceObject only)");
            return -1;
        }
        Set<Integer> before = snapshotWallIds();
        try {
            HInventoryItem invItem = t.kind > 0 ? inventory.takeWall(t.kind) : null;
            if (invItem == null) return -1;
            int invId = invItem.getId();
            String pos = WallFurniInfo.normalize(t.wallPos);
            if (pos.isEmpty()) pos = ":w=0,0 l=0,0 l";
            WallFurniInfo place = new WallFurniInfo(invId, pos);
            place.setFurniId(invId);
            log.accept("place INV wall " + t.className + " #" + invId + " PlaceObject " + place.placeString());
            ext.sendToServer(new HPacket("PlaceObject", HMessage.Direction.TOSERVER, place.placeString()));
            sleep(dly() + 80);
            if (room.wallById(invId) != null) return invId;
            return newestWall(t, before);
        } catch (Exception ex) {
            log.accept("INV wall fail: " + ex.getMessage());
            return -1;
        }
    }

    private Set<Integer> snapshotFloorIds() {
        Set<Integer> ids = new HashSet<>();
        for (HFloorItem f : room.floorItems()) ids.add(f.getId());
        return ids;
    }

    private Set<Integer> snapshotWallIds() {
        Set<Integer> ids = new HashSet<>();
        for (HWallItem w : room.wallItems()) ids.add(w.getId());
        return ids;
    }

    private int newestFloor(Models.Target t, Set<Integer> before) {
        return room.floorItems().stream()
                .filter(f -> !before.contains(f.getId()))
                .filter(f -> classOfFloor(f).equalsIgnoreCase(t.className) || f.getTypeId() == t.kind)
                .mapToInt(HFloorItem::getId)
                .max()
                .orElse(-1);
    }

    private int newestWall(Models.Target t, Set<Integer> before) {
        return room.wallItems().stream()
                .filter(w -> !before.contains(w.getId()))
                .filter(w -> classOfWall(w).equalsIgnoreCase(t.className) || w.getTypeId() == t.kind)
                .mapToInt(HWallItem::getId)
                .max()
                .orElse(-1);
    }

    private int[] footprintOf(Models.Target t) {
        Models.FurniMeta meta = catalog.floorByClass(t.className);
        if (meta == null) meta = catalog.floorByType(t.kind);
        if (meta != null && meta.w > 0 && meta.h > 0) {
            if (t.rotation == 2 || t.rotation == 6) return new int[]{meta.h, meta.w};
            return new int[]{meta.w, meta.h};
        }
        return new int[]{1, 1};
    }

    private HFloorItem pickFloor(Map<String, List<HFloorItem>> pools, Models.Target t, Set<Integer> used) {
        if (t.id > 0) {
            HFloorItem byId = room.floorById(t.id);
            if (byId != null && !used.contains(byId.getId()) && !isStackMagicItem(byId)) {
                log.accept("id hit #" + t.id + " " + t.className);
                return byId;
            }
            if (room.wallById(t.id) != null) {
                log.accept("saved id #" + t.id + " is wall now - matching " + t.className + " by position");
            } else if (byId == null) {
                log.accept("saved id #" + t.id + " not in floor - matching " + t.className + " by position");
            }
        }

        List<HFloorItem> candidates = new ArrayList<>();
        if (t.className != null) {
            List<HFloorItem> pool = pools.get(t.className.toLowerCase(Locale.ROOT));
            if (pool != null) {
                for (HFloorItem i : pool) {
                    if (!used.contains(i.getId()) && !isStackMagicItem(i)) candidates.add(i);
                }
            }
        }
        if (candidates.isEmpty() && t.kind > 0) {
            for (HFloorItem f : room.floorItems()) {
                if (used.contains(f.getId()) || isStackMagicItem(f)) continue;
                if (f.getTypeId() == t.kind) candidates.add(f);
            }
        }
        if (candidates.isEmpty()) return null;

        HFloorItem best = candidates.stream()
                .map(i -> {
                    HFloorItem live = room.floorById(i.getId());
                    return live != null ? live : i;
                })
                .sorted(Comparator
                        .comparingInt((HFloorItem i) -> {
                            HPoint p = i.getTile();
                            int dir = i.getFacing() == null ? 0 : i.getFacing().ordinal();
                            boolean exact = p.getX() == t.x && p.getY() == t.y
                                    && dir == t.rotation
                                    && Math.abs(p.getZ() - t.z) <= Z_EPS;
                            return exact ? 0 : 1;
                        })
                        .thenComparingInt(i -> {
                            String want = t.state == null ? "" : t.state;
                            return statesEqual(floorStateOf(i), want) ? 0 : 1;
                        })
                        .thenComparingInt(i -> {
                            HPoint p = i.getTile();
                            return (p.getX() == t.x && p.getY() == t.y) ? 0 : 1;
                        })
                        .thenComparingInt(i -> {
                            HPoint p = i.getTile();
                            return Math.abs(p.getX() - t.x) + Math.abs(p.getY() - t.y);
                        })
                        .thenComparingDouble(i -> Math.abs(i.getTile().getZ() - t.z))
                        .thenComparingInt(i -> i.getId() == t.id ? 0 : 1))
                .findFirst()
                .orElse(null);
        if (best != null) {
            HPoint p = best.getTile();
            int dir = best.getFacing() == null ? 0 : best.getFacing().ordinal();
            boolean exact = p.getX() == t.x && p.getY() == t.y
                    && dir == t.rotation
                    && Math.abs(p.getZ() - t.z) <= Z_EPS;
            if (exact) {
                log.accept("pos hit #" + best.getId() + " " + t.className
                        + " already @" + t.x + "," + t.y + ",z" + t.z);
            } else {
                log.accept("class hit #" + best.getId() + " " + t.className
                        + " @" + p.getX() + "," + p.getY() + ",z" + p.getZ()
                        + " for target @" + t.x + "," + t.y + ",z" + t.z);
            }
        }
        return best;
    }

    private HWallItem pickWall(Map<String, List<HWallItem>> pools, Models.Target t, Set<Integer> used) {
        if (t.id > 0) {
            HWallItem byId = room.wallById(t.id);
            if (byId != null && !used.contains(byId.getId())) {
                log.accept("id hit wall #" + t.id + " " + t.className);
                return byId;
            }
        }
        String wantState = t.state == null ? "" : t.state;
        List<HWallItem> candidates = new ArrayList<>();
        List<HWallItem> pool = t.className == null ? null : pools.get(t.className.toLowerCase(Locale.ROOT));
        if (pool != null) candidates.addAll(pool);
        for (HWallItem w : room.wallItems()) {
            if (used.contains(w.getId())) continue;
            if (t.kind > 0 && w.getTypeId() == t.kind) {
                if (!candidates.contains(w)) candidates.add(w);
            }
        }
        return candidates.stream()
                .filter(i -> !used.contains(i.getId()))
                .sorted(Comparator
                        .comparingInt((HWallItem i) -> {
                            String st = i.getState() == null ? "" : i.getState();
                            return st.equals(wantState) ? 0 : 1;
                        })
                        .thenComparingInt(i -> i.getId() == t.id ? 0 : 1)
                        .thenComparingInt(i -> Math.abs(i.getId() - t.id)))
                .findFirst()
                .orElse(null);
    }

    private void removeFloorPool(Map<String, List<HFloorItem>> pools, String className, int id) {
        List<HFloorItem> pool = pools.get(className.toLowerCase(Locale.ROOT));
        if (pool != null) pool.removeIf(i -> i.getId() == id);
    }

    private void removeWallPool(Map<String, List<HWallItem>> pools, String className, int id) {
        List<HWallItem> pool = pools.get(className.toLowerCase(Locale.ROOT));
        if (pool != null) pool.removeIf(i -> i.getId() == id);
    }

    private String classOfFloor(HFloorItem f) {
        if (f.getTypeId() < 0) {
            String sc = f.getStaticClass();
            if (sc != null && !sc.isEmpty()) return FurniCatalog.normalize(sc);
        }
        String cn = catalog.floorClass(f.getTypeId());
        return cn.isEmpty() ? "typeid_" + f.getTypeId() : cn;
    }

    private String classOfWall(HWallItem w) {
        String cn = catalog.wallClass(w.getTypeId());
        return cn.isEmpty() ? "typeid_" + w.getTypeId() : cn;
    }

    private static boolean tileCoversItem(int needW, int needH, int tileW, int tileH) {
        if (tileW <= 0 || tileH <= 0) return false;
        return Math.max(tileW, tileH) >= Math.max(needW, needH)
                && Math.min(tileW, tileH) >= Math.min(needW, needH);
    }

    private static boolean coversPoint(int ox, int oy, int dir, int w, int h, int x, int y) {
        int ww = (dir == 2 || dir == 6) ? h : w;
        int hh = (dir == 2 || dir == 6) ? w : h;
        if (ww < 1) ww = 1;
        if (hh < 1) hh = 1;
        return x >= ox && x < ox + ww && y >= oy && y < oy + hh;
    }

    private static List<int[]> originCandidates(int tx, int ty, int tw, int th, int preferDir) {
        List<int[]> list = new ArrayList<>();
        Set<Integer> seenDir = new LinkedHashSet<>();
        int[] dirs = new int[]{preferDir, 0, 2, 4, 6};
        for (int dir : dirs) {
            if (!seenDir.add(dir)) continue;
            int ew = (dir == 2 || dir == 6) ? th : tw;
            int eh = (dir == 2 || dir == 6) ? tw : th;
            if (ew < 1) ew = 1;
            if (eh < 1) eh = 1;
            for (int dx = 0; dx < ew; dx++) {
                for (int dy = 0; dy < eh; dy++) {
                    list.add(new int[]{tx - dx, ty - dy, dir});
                }
            }
        }
        list.sort(Comparator
                .comparingInt((int[] o) -> Math.abs(o[0] - tx) + Math.abs(o[1] - ty))
                .thenComparingInt(o -> o[2] == preferDir ? 0 : 1));
        return list;
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class TileInfo {
        final HFloorItem item;
        final String id;
        final int w;
        final int h;

        TileInfo(HFloorItem item, String id, int w, int h) {
            this.item = item;
            this.id = id;
            this.w = w;
            this.h = h;
        }
    }
}
