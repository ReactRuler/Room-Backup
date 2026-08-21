package roommount.core;

import gearth.extensions.IExtension;
import gearth.extensions.parsers.HInventoryItem;
import gearth.extensions.parsers.HProductType;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class InventoryTracker {
    public enum State { UNAVAILABLE, LOADING, LOADED }

    private final IExtension extension;
    private final Map<Integer, HInventoryItem> byPlacement = new HashMap<>();
    private final Map<Integer, LinkedList<HInventoryItem>> floorByType = new HashMap<>();
    private final Map<Integer, LinkedList<HInventoryItem>> wallByType = new HashMap<>();
    private List<HInventoryItem> buffer;
    private volatile State state = State.UNAVAILABLE;
    private volatile boolean virtualRequest;
    private Consumer<Void> onChange = x -> {};

    public InventoryTracker(IExtension extension) {
        this.extension = extension;
        extension.intercept(HMessage.Direction.TOCLIENT, "FurniList", this::onFurniList);
        extension.intercept(HMessage.Direction.TOCLIENT, "FurniListAddOrUpdate", this::onAddOrUpdate);
        extension.intercept(HMessage.Direction.TOCLIENT, "FurniListRemove", m -> remove(m.getPacket().readInteger()));
    }

    public void setOnChange(Consumer<Void> onChange) {
        this.onChange = onChange == null ? x -> {} : onChange;
    }

    public State getState() {
        return state;
    }

    public void request() {
        virtualRequest = true;
        extension.sendToServer(new HPacket("RequestFurniInventory", HMessage.Direction.TOSERVER));
    }

    public HInventoryItem takeFloor(int typeId) {
        LinkedList<HInventoryItem> list = floorByType.get(typeId);
        if (list == null || list.isEmpty()) return null;
        HInventoryItem item = list.pollFirst();
        byPlacement.remove(item.getPlacementId());
        return item;
    }

    public HInventoryItem takeWall(int typeId) {
        LinkedList<HInventoryItem> list = wallByType.get(typeId);
        if (list == null || list.isEmpty()) return null;
        HInventoryItem item = list.pollFirst();
        byPlacement.remove(item.getPlacementId());
        return item;
    }

    public int floorCount(int typeId) {
        LinkedList<HInventoryItem> list = floorByType.get(typeId);
        return list == null ? 0 : list.size();
    }

    private void onFurniList(HMessage msg) {
        try {
            if (virtualRequest) msg.setBlocked(true);
            HPacket packet = msg.getPacket();
            int total = packet.readInteger();
            int index = packet.readInteger();
            if (index == 0) {
                clear();
                buffer = new ArrayList<>();
                state = State.LOADING;
                fire();
            }
            packet.resetReadIndex();
            HInventoryItem[] items = HInventoryItem.parse(packet);
            if (buffer == null) buffer = new ArrayList<>();
            for (HInventoryItem item : items) buffer.add(item);
            if (index == total - 1) {
                for (HInventoryItem item : buffer) upsert(item);
                buffer = null;
                virtualRequest = false;
                state = State.LOADED;
                fire();
            }
        } catch (Throwable ex) {
            virtualRequest = false;
            state = State.UNAVAILABLE;
            buffer = null;
        }
    }

    private void onAddOrUpdate(HMessage msg) {
        HPacket packet = msg.getPacket();
        int count = packet.readInteger();
        for (int i = 0; i < count; i++) {
            upsert(new HInventoryItem(packet));
        }
        fire();
    }

    private void clear() {
        byPlacement.clear();
        floorByType.clear();
        wallByType.clear();
    }

    private void upsert(HInventoryItem item) {
        remove(item.getPlacementId());
        byPlacement.put(item.getPlacementId(), item);
        Map<Integer, LinkedList<HInventoryItem>> map =
                item.getType() == HProductType.FloorItem ? floorByType : wallByType;
        map.computeIfAbsent(item.getTypeId(), k -> new LinkedList<>()).add(item);
        if (state == State.UNAVAILABLE) state = State.LOADED;
    }

    private void remove(int placementId) {
        HInventoryItem old = byPlacement.remove(placementId);
        if (old == null) return;
        Map<Integer, LinkedList<HInventoryItem>> map =
                old.getType() == HProductType.FloorItem ? floorByType : wallByType;
        LinkedList<HInventoryItem> list = map.get(old.getTypeId());
        if (list != null) list.removeIf(i -> i.getPlacementId() == placementId);
    }

    private void fire() {
        try {
            onChange.accept(null);
        } catch (Exception ignored) {
        }
    }
}
