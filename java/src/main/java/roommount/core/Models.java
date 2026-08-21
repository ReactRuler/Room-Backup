package roommount.core;

public final class Models {
    private Models() {}

    public enum MissingMode { SKIP, STOP, PLACE_NEW }
    public enum SourceMode { BC_FIRST, INV_FIRST, BC_ONLY, INV_ONLY }

    public static final class FurniMeta {
        public final String kind;
        public final String className;
        public final int w;
        public final int h;
        public final int offerId;
        public final boolean bc;
        public final String name;

        public FurniMeta(String kind, String className, int w, int h, int offerId, boolean bc, String name) {
            this.kind = kind;
            this.className = className;
            this.w = w;
            this.h = h;
            this.offerId = offerId;
            this.bc = bc;
            this.name = name == null || name.isEmpty() ? className : name;
        }
    }

    public static final class Target {
        public String type;
        public int id;
        public int kind;
        public String className;
        public String name;
        public int x;
        public int y;
        public double z;
        public int rotation;
        public String state;
        public String wallPos;
        public int offerId;
        public boolean bc;
    }

    public static final class MountOptions {
        public MissingMode missingMode = MissingMode.SKIP;
        public SourceMode sourceMode = SourceMode.BC_FIRST;
        public int delayMs = 320;
        public java.util.function.IntSupplier delayLive;
        public boolean force = false;

        public int liveDelay() {
            if (delayLive != null) {
                try {
                    return Math.max(40, delayLive.getAsInt());
                } catch (Exception ignored) {
                }
            }
            return Math.max(40, delayMs);
        }
    }
}
