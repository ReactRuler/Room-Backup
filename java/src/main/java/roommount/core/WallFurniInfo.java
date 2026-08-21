package roommount.core;

public final class WallFurniInfo {
    private long furniId;
    private int x;
    private int y;
    private int xOffset;
    private int yOffset;
    private boolean isLeft;

    public WallFurniInfo(long furniId, String info) {
        this.furniId = furniId;
        String[] infoSplit = info.trim().split(" ");
        String[] pointInfo = infoSplit[0].substring(3).split(",");
        this.x = Integer.parseInt(pointInfo[0]);
        this.y = Integer.parseInt(pointInfo[1]);
        String[] offsetInfo = infoSplit[1].substring(2).split(",");
        this.xOffset = Integer.parseInt(offsetInfo[0]);
        this.yOffset = Integer.parseInt(offsetInfo[1]);
        this.isLeft = infoSplit[2].equals("l");
    }

    public WallFurniInfo(long furniId, int x, int y, int xOffset, int yOffset, boolean isLeft) {
        this.furniId = furniId;
        this.x = x;
        this.y = y;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.isLeft = isLeft;
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            String s = raw.trim();
            if (Character.isDigit(s.charAt(0))) {
                s = s.split(" ", 2)[1];
            }
            return new WallFurniInfo(0, s).moveString();
        } catch (Exception ex) {
            return raw.trim();
        }
    }

    public static boolean samePos(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        return na.equals(nb);
    }

    public String moveString() {
        return String.format(":w=%d,%d l=%d,%d %c", this.x, this.y, this.xOffset, this.yOffset, this.isLeft ? 'l' : 'r');
    }

    public String placeString() {
        return String.format("%d %s", this.furniId, this.moveString());
    }

    public long getFurniId() {
        return this.furniId;
    }

    public void setFurniId(long furniId) {
        this.furniId = furniId;
    }
}
