/*
 * Decompiled with CFR 0.152.
 */
package gearth.extensions.parsers;

import gearth.protocol.HPacket;
import java.util.ArrayList;

public class HStuff {
    public static Object[] readData(HPacket packet, int category) {
        ArrayList<Object> values = new ArrayList<Object>();
        switch (category & 0xFF) {
            case 0: {
                values.add(packet.readString());
                break;
            }
            case 1: {
                int count = packet.readInteger();
                values.add(count);
                for (int j = 0; j < count; ++j) {
                    values.add(packet.readString());
                    values.add(packet.readString());
                }
                break;
            }
            case 2: {
                int count = packet.readInteger();
                values.add(count);
                for (int j = 0; j < count; ++j) {
                    values.add(packet.readString());
                }
                break;
            }
            case 3: {
                values.add(packet.readString());
                values.add(packet.readInteger());
                break;
            }
            case 5: {
                int count = packet.readInteger();
                values.add(count);
                for (int j = 0; j < count; ++j) {
                    values.add(packet.readInteger());
                }
                break;
            }
            case 6: {
                values.add(packet.readString());
                values.add(packet.readInteger());
                values.add(packet.readInteger());
                int count = packet.readInteger();
                values.add(count);
                for (int j = 0; j < count; ++j) {
                    int score = packet.readInteger();
                    values.add(score);
                    int subCount = packet.readInteger();
                    values.add(subCount);
                    for (int k = 0; k < subCount; ++k) {
                        values.add(packet.readString());
                    }
                }
                break;
            }
            case 7: {
                values.add(packet.readString());
                values.add(packet.readInteger());
                values.add(packet.readInteger());
            }
        }
        if ((category & 0xFF00 & 0x100) > 0) {
            values.add(packet.readInteger());
            values.add(packet.readInteger());
        }
        return values.toArray();
    }
}

