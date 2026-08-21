package roommount.core;

import gearth.extensions.IExtension;
import gearth.extensions.parsers.HFloorItem;
import gearth.extensions.parsers.HWallItem;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class RoomTracker {
    private final IExtension extension;
    private final Map<Integer, HFloorItem> floor = new ConcurrentHashMap<>();
    private final Map<Integer, HWallItem> wall = new ConcurrentHashMap<>();
    private volatile boolean inRoom;
    private volatile int roomId;
    private volatile char[][] floorplan;
    private Consumer<Void> onChange = x -> {};
    private Consumer<String> onLog = s -> {};
    private final AtomicLong floorEpoch = new AtomicLong();
    private volatile CountDownLatch floorLatch;

    public RoomTracker(IExtension extension) {
        this.extension = extension;
        interceptClient("Objects", this::onObjects);
        interceptClient("ObjectsMessageEvent", this::onObjects);
        interceptClient("ObjectAdd", this::onObjectAdd);
        interceptClient("ObjectAddMessageEvent", this::onObjectAdd);
        interceptClient("ObjectRemove", this::onObjectRemove);
        interceptClient("ObjectRemoveMessageEvent", this::onObjectRemove);
        interceptClient("ObjectUpdate", this::onObjectUpdate);
        interceptClient("ObjectUpdateMessageEvent", this::onObjectUpdate);
        interceptClient("ObjectDataUpdate", this::onObjectDataUpdate);
        interceptClient("StuffDataUpdate", this::onObjectDataUpdate);
        interceptClient("ObjectsDataUpdate", this::onObjectsDataUpdate);
        interceptClient("MultipleStuffDataUpdate", this::onObjectsDataUpdate);
        interceptClient("Items", this::onWallItems);
        interceptClient("ItemsMessageEvent", this::onWallItems);
        interceptClient("ItemAdd", this::onWallAdd);
        interceptClient("ItemAddMessageEvent", this::onWallAdd);
        interceptClient("ItemRemove", this::onWallRemove);
        interceptClient("ItemRemoveMessageEvent", this::onWallRemove);
        interceptClient("ItemUpdate", this::onWallUpdate);
        interceptClient("ItemUpdateMessageEvent", this::onWallUpdate);
        interceptClient("FloorHeightMap", this::onFloorPlan);
        interceptClient("FloorHeightMapMessageEvent", this::onFloorPlan);
        interceptClient("RoomEntryInfo", this::onRoomEntry);
        interceptClient("RoomEntryInfoMessageEvent", this::onRoomEntry);
        interceptClient("RoomReady", m -> reset());
        interceptClient("RoomReadyMessageEvent", m -> reset());
        interceptClient("CloseConnection", m -> reset());
        extension.intercept(HMessage.Direction.TOSERVER, "Quit", m -> reset());
    }

    private void interceptClient(String name, gearth.extensions.ExtensionBase.MessageListener listener) {
        extension.intercept(HMessage.Direction.TOCLIENT, name, listener);
    }

    public void setOnChange(Consumer<Void> onChange) {
        this.onChange = onChange == null ? x -> {} : onChange;
    }

    public void setOnLog(Consumer<String> onLog) {
        this.onLog = onLog == null ? s -> {} : onLog;
    }

    public boolean inRoom() {
        return inRoom;
    }

    public int getRoomId() {
        return roomId;
    }

    public void reset() {
        floor.clear();
        wall.clear();
        floorplan = null;
        inRoom = false;
        roomId = 0;
        fire();
    }

    public boolean hasFloorplan() {
        return floorplan != null;
    }

    public char floorHeight(int x, int y) {
        char[][] plan = floorplan;
        if (plan == null || x < 0 || y < 0 || x >= plan.length || y >= plan[x].length) return 'x';
        return plan[x][y];
    }

    public int planWidth() {
        char[][] plan = floorplan;
        return plan == null ? 0 : plan.length;
    }

    public int planHeight() {
        char[][] plan = floorplan;
        if (plan == null || plan.length == 0) return 0;
        int h = 0;
        for (char[] col : plan) {
            if (col != null && col.length > h) h = col.length;
        }
        return h;
    }

    public boolean inPlanBounds(int x, int y) {
        char[][] plan = floorplan;
        if (plan == null) return x >= 0 && y >= 0;
        return x >= 0 && y >= 0 && x < plan.length && plan[x] != null && y < plan[x].length;
    }

    public boolean hasFloorItemAt(int x, int y) {
        for (HFloorItem f : floor.values()) {
            if (f == null || f.getTile() == null) continue;
            if (f.getTile().getX() == x && f.getTile().getY() == y) return true;
        }
        return false;
    }

    public boolean isWalkableOrOccupied(int x, int y) {
        if (x < 0 || y < 0) return false;
        if (!hasFloorplan()) return true;
        if (floorHeight(x, y) != 'x') return true;
        return hasFloorItemAt(x, y);
    }

    private void onFloorPlan(HMessage msg) {
        try {
            HPacket packet = msg.getPacket();
            packet.resetReadIndex();
            packet.readByte();
            packet.readInteger();
            String raw = packet.readString();
            String[] split = raw.split("\r");
            if (split.length == 0 || split[0].isEmpty()) return;
            char[][] next = new char[split[0].length()][];
            for (int x = 0; x < split[0].length(); x++) {
                next[x] = new char[split.length];
                for (int y = 0; y < split.length; y++) {
                    next[x][y] = y < split[y].length() ? split[y].charAt(x) : 'x';
                }
            }
            floorplan = next;
            onLog.accept("Parsed floorplan " + next.length + "x" + split.length);
            fire();
        } catch (Exception ex) {
            onLog.accept("Floorplan parse error: " + ex.getMessage());
        }
    }

    public Collection<HFloorItem> floorItems() {
        return new ArrayList<>(floor.values());
    }

    public Collection<HWallItem> wallItems() {
        return new ArrayList<>(wall.values());
    }

    public HFloorItem floorById(int id) {
        return floor.get(id);
    }

    public HWallItem wallById(int id) {
        return wall.get(id);
    }

    public Map<Integer, HFloorItem> floorMap() {
        return new HashMap<>(floor);
    }

    public void requestReload() {
        try {
            extension.sendToServer(new HPacket("GetHeightMap", HMessage.Direction.TOSERVER));
        } catch (Exception ex) {
            onLog.accept("Room reload request failed: " + ex.getMessage());
        }
    }

    public boolean forceRefreshFloor(long timeoutMs) {
        onLog.accept("Refreshing floor Objects…");
        floor.clear();
        CountDownLatch latch = new CountDownLatch(1);
        floorLatch = latch;
        long before = floorEpoch.get();
        requestReload();
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (floorLatch == latch) floorLatch = null;
        boolean got = floorEpoch.get() != before;
        if (got) waitForFurniSettle(400, Math.min(4000, timeoutMs));
        onLog.accept(got
                ? ("Floor refreshed: " + floor.size() + " items")
                : ("No new Objects packet — pick up & re-place the Magic Stack Tile, or leave+enter the room"));
        return got;
    }

    public void waitForFurniSettle(long quietMs, long maxWaitMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, maxWaitMs);
        int lastF = -1;
        int lastW = -1;
        long lastChange = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            int f = floor.size();
            int w = wall.size();
            if (f != lastF || w != lastW) {
                lastF = f;
                lastW = w;
                lastChange = System.currentTimeMillis();
                onLog.accept("Loading room furni… floor=" + f + " wall=" + w);
            } else if (f + w > 0 && System.currentTimeMillis() - lastChange >= quietMs) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public boolean ensureFloorLoaded(long timeoutMs) {
        if (!floor.isEmpty()) return true;
        onLog.accept("Floor list empty — refreshing room data…");
        CountDownLatch latch = new CountDownLatch(1);
        floorLatch = latch;
        long before = floorEpoch.get();
        requestReload();
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (floorLatch == latch) floorLatch = null;
        boolean ok = !floor.isEmpty() || floorEpoch.get() != before;
        if (floor.isEmpty()) {
            onLog.accept("Still no floor items — re-enter the room once, then save again");
        } else {
            onLog.accept("Floor items loaded: " + floor.size());
        }
        return !floor.isEmpty();
    }

    private void onRoomEntry(HMessage msg) {
        try {
            HPacket p = msg.getPacket();
            p.resetReadIndex();
            roomId = p.readInteger();
            inRoom = true;
            fire();
        } catch (Exception ignored) {
        }
    }

    private void onObjects(HMessage msg) {
        try {
            HPacket packet = msg.getPacket();
            packet.resetReadIndex();
            HFloorItem[] items = HFloorItem.parse(packet);
            int unread = packet.getBytesLength() - packet.getReadIndex();
            Map<Integer, HFloorItem> next = new HashMap<>();
            Map<String, Integer> classCounts = new HashMap<>();
            for (HFloorItem item : items) {
                if (item == null) continue;
                next.put(item.getId(), item);
                String cn = item.getTypeId() < 0 && item.getStaticClass() != null && !item.getStaticClass().isEmpty()
                        ? item.getStaticClass()
                        : ("type:" + item.getTypeId());
                classCounts.merge(cn, 1, Integer::sum);
            }
            int before = floor.size();
            floor.putAll(next);
            inRoom = true;
            floorEpoch.incrementAndGet();
            CountDownLatch latch = floorLatch;
            if (latch != null) latch.countDown();
            onLog.accept("Parsed floor chunk +" + next.size()
                    + " → total " + floor.size()
                    + (before > 0 ? (" (was " + before + ")") : "")
                    + (unread > 0 ? (" (unread bytes=" + unread + ")") : ""));
            if (!classCounts.isEmpty()) {
                StringBuilder sb = new StringBuilder("Floor kinds: ");
                int n = 0;
                for (Map.Entry<String, Integer> e : classCounts.entrySet()) {
                    if (n++ > 0) sb.append(", ");
                    sb.append(e.getKey()).append("x").append(e.getValue());
                    if (n >= 12) {
                        sb.append(", …");
                        break;
                    }
                }
                onLog.accept(sb.toString());
            }
            fire();
        } catch (Exception ex) {
            onLog.accept("Floor parse error: " + ex.getMessage());
        }
    }

    private void onObjectAdd(HMessage msg) {
        try {
            HPacket packet = msg.getPacket();
            packet.resetReadIndex();
            HFloorItem item = new HFloorItem(packet);
            try {
                item.setOwnerName(packet.readString());
            } catch (Exception ignored) {
            }
            floor.put(item.getId(), item);
            inRoom = true;
            fire();
        } catch (Exception ex) {
            onLog.accept("ObjectAdd parse error: " + ex.getMessage());
        }
    }

    private void onObjectRemove(HMessage msg) {
        try {
            HPacket p = msg.getPacket();
            p.resetReadIndex();
            String idStr = p.readString();
            floor.remove(Integer.parseInt(idStr));
            fire();
        } catch (Exception ignored) {
        }
    }

    private void onObjectUpdate(HMessage msg) {
        try {
            HPacket packet = msg.getPacket();
            packet.resetReadIndex();
            HFloorItem item = new HFloorItem(packet);
            HFloorItem old = floor.get(item.getId());
            if (old != null && old.getOwnerName() != null) {
                item.setOwnerName(old.getOwnerName());
            }
            floor.put(item.getId(), item);
            fire();
        } catch (Exception ex) {
            onLog.accept("ObjectUpdate parse error: " + ex.getMessage());
        }
    }

    private void onObjectDataUpdate(HMessage msg) {
        try {
            HPacket packet = msg.getPacket();
            packet.resetReadIndex();
            int id = Integer.parseInt(packet.readString());
            gearth.extensions.parsers.stuffdata.IStuffData stuff =
                    gearth.extensions.parsers.stuffdata.IStuffData.read(packet);
            HFloorItem item = floor.get(id);
            if (item != null && stuff != null) {
                item.setStuff(stuff);
                fire();
            }
        } catch (Exception ex) {
            onLog.accept("ObjectDataUpdate parse error: " + ex.getMessage());
        }
    }

    private void onObjectsDataUpdate(HMessage msg) {
        try {
            HPacket packet = msg.getPacket();
            packet.resetReadIndex();
            int n = packet.readInteger();
            for (int i = 0; i < n; i++) {
                int id = packet.readInteger();
                gearth.extensions.parsers.stuffdata.IStuffData stuff =
                        gearth.extensions.parsers.stuffdata.IStuffData.read(packet);
                HFloorItem item = floor.get(id);
                if (item != null && stuff != null) {
                    item.setStuff(stuff);
                }
            }
            fire();
        } catch (Exception ex) {
            onLog.accept("ObjectsDataUpdate parse error: " + ex.getMessage());
        }
    }

    private void onWallItems(HMessage msg) {
        try {
            HPacket packet = msg.getPacket();
            packet.resetReadIndex();
            HWallItem[] items = HWallItem.parse(packet);
            Map<Integer, HWallItem> next = new HashMap<>();
            for (HWallItem item : items) {
                if (item != null) next.put(item.getId(), item);
            }
            int before = wall.size();
            wall.putAll(next);
            inRoom = true;
            onLog.accept("Parsed wall chunk +" + next.size()
                    + " → total " + wall.size()
                    + (before > 0 ? (" (was " + before + ")") : ""));
            fire();
            if (floor.isEmpty() && !wall.isEmpty()) {
                requestReload();
            }
        } catch (Exception ex) {
            onLog.accept("Wall parse error: " + ex.getMessage());
        }
    }

    private void onWallAdd(HMessage msg) {
        try {
            HPacket packet = msg.getPacket();
            packet.resetReadIndex();
            HWallItem item = new HWallItem(packet);
            wall.put(item.getId(), item);
            inRoom = true;
            fire();
        } catch (Exception ignored) {
        }
    }

    private void onWallRemove(HMessage msg) {
        try {
            HPacket p = msg.getPacket();
            p.resetReadIndex();
            String idStr = p.readString();
            wall.remove(Integer.parseInt(idStr));
            fire();
        } catch (Exception ignored) {
        }
    }

    private void onWallUpdate(HMessage msg) {
        try {
            HPacket packet = msg.getPacket();
            packet.resetReadIndex();
            HWallItem item = new HWallItem(packet);
            wall.put(item.getId(), item);
            fire();
        } catch (Exception ignored) {
        }
    }

    private void fire() {
        try {
            onChange.accept(null);
        } catch (Exception ignored) {
        }
    }

    public List<HFloorItem> stackTiles(FurniCatalog catalog) {
        List<HFloorItem> out = new ArrayList<>();
        for (HFloorItem item : floor.values()) {
            String cn = catalog.floorClass(item.getTypeId());
            if (FurniCatalog.isStackMagic(cn)) {
                out.add(item);
                continue;
            }
            int tid = item.getTypeId();
            if (FurniCatalog.isStackMagicTypeId(tid)) out.add(item);
        }
        return out;
    }
}
